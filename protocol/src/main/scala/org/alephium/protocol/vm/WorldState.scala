// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm

import akka.util.ByteString

import org.alephium.io._
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.serde.{Serde, SerdeError}
import org.alephium.util.AVector

trait WorldState[T, R1, R2, R3] {
  def outputState: MutableTrie[TxOutputRef, TxOutput, R1]
  def contractState: MutableTrie[Hash, ContractState, R2]
  def codeState: MutableTrie[Hash, WorldState.CodeRecord, R3]

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getAsset(outputRef: AssetOutputRef): IOResult[AssetOutput] = {
    // we use asInstanceOf for optimization
    getOutput(outputRef) match {
      case Right(_: ContractOutput) => Left(WorldState.expectedAssetError)
      case result                   => result.asInstanceOf[IOResult[AssetOutput]]
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def getAssetOpt(outputRef: AssetOutputRef): IOResult[Option[AssetOutput]] = {
    // we use asInstanceOf for optimization
    getOutputOpt(outputRef) match {
      case Right(Some(_: ContractOutput)) => Left(WorldState.expectedAssetError)
      case result                         => result.asInstanceOf[IOResult[Option[AssetOutput]]]
    }
  }

  def getOutput(outputRef: TxOutputRef): IOResult[TxOutput] = {
    outputState.get(outputRef)
  }

  def getOutputOpt(outputRef: TxOutputRef): IOResult[Option[TxOutput]] = {
    outputState.getOpt(outputRef)
  }

  def existOutput(outputRef: TxOutputRef): IOResult[Boolean] = {
    outputState.exist(outputRef)
  }

  def getContractState(key: Hash): IOResult[ContractState] = {
    contractState.get(key)
  }

  def getContractCode(key: Hash): IOResult[WorldState.CodeRecord] = {
    codeState.get(key)
  }

  def getContractAsset(key: Hash): IOResult[ContractOutput] = {
    for {
      state  <- getContractState(key)
      output <- getContractAsset(state.contractOutputRef)
    } yield output
  }

  def getContractAsset(outputRef: ContractOutputRef): IOResult[ContractOutput] = {
    for {
      outputRaw <- getOutput(outputRef)
      output <- outputRaw match {
        case _: AssetOutput =>
          val error = s"ContractOutput expected, but got AssetOutput at $outputRef"
          Left(IOError.Other(new RuntimeException(error)))
        case o: ContractOutput =>
          Right(o)
      }
    } yield output
  }

  def getContractObj(key: Hash): IOResult[StatefulContractObject] = {
    for {
      state <- getContractState(key)
      code  <- getContractCode(state.codeHash)
    } yield state.toObject(key, code.code)
  }

  def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[T]

  def createContractUnsafe(
      code: StatefulContract.HalfDecoded,
      fields: AVector[Val],
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[T]

  def updateContractUnsafe(key: Hash, fields: AVector[Val]): IOResult[T]

  def updateContract(key: Hash, outputRef: ContractOutputRef, output: ContractOutput): IOResult[T]

  protected[vm] def updateContract(key: Hash, state: ContractState): IOResult[T]

  def removeAsset(outputRef: TxOutputRef): IOResult[T]

  def removeContract(contractKey: Hash): IOResult[T]

  protected def removeContractCode(
      currentState: ContractState,
      currentRecord: WorldState.CodeRecord
  ): IOResult[R3] = {
    if (currentRecord.refCount > 1) {
      codeState.put(
        currentState.codeHash,
        currentRecord.copy(refCount = currentRecord.refCount - 1)
      )
    } else {
      codeState.remove(currentState.codeHash)
    }
  }

  def persist(): IOResult[WorldState.Persisted]

  def getPreOutputsForAssetInputs(
      tx: TransactionAbstract
  ): IOResult[Option[AVector[AssetOutput]]] = {
    val inputs = tx.unsigned.inputs
    inputs.foldE[IOError, Option[AVector[AssetOutput]]](Some(AVector.ofSize(inputs.length))) {
      case (Some(outputs), input) =>
        getAssetOpt(input.outputRef).map {
          case Some(output) => Some(outputs :+ output)
          case None         => None
        }
      case (None, _) => Right(None)
    }
  }

  def getPreOutputs(tx: Transaction): IOResult[AVector[TxOutput]] = {
    for {
      fixedInputs    <- tx.unsigned.inputs.mapE { input => getOutput(input.outputRef) }
      contractInputs <- tx.contractInputs.mapE { outputRef => getOutput(outputRef) }
    } yield (fixedInputs ++ contractInputs)
  }

  def containsAllInputs(tx: TransactionTemplate): IOResult[Boolean] = {
    tx.unsigned.inputs.forallE(input => existOutput(input.outputRef))
  }

  def getAssetOutputs(
      outputRefPrefix: ByteString,
      maxOutputs: Int,
      predicate: (TxOutputRef, TxOutput) => Boolean
  ): IOResult[AVector[(AssetOutputRef, AssetOutput)]]
}

sealed abstract class MutableWorldState extends WorldState[Unit, Unit, Unit, Unit] {
  def useContractAsset(contractId: ContractId): IOResult[(ContractOutputRef, ContractOutput)] = {
    for {
      state  <- getContractState(contractId)
      output <- getContractAsset(state.contractOutputRef)
      _      <- removeAsset(state.contractOutputRef)
    } yield (state.contractOutputRef, output)
  }

  def updateContractUnsafe(key: Hash, fields: AVector[Val]): IOResult[Unit] = {
    for {
      oldState <- getContractState(key)
      newState <- updateContract(key, oldState.updateFieldsUnsafe(fields))
    } yield newState
  }

  def updateContract(
      key: Hash,
      outputRef: ContractOutputRef,
      output: ContractOutput
  ): IOResult[Unit]
}

// scalastyle:off no.whitespace.after.left.bracket
sealed abstract class ImmutableWorldState
    extends WorldState[
      ImmutableWorldState,
      SparseMerkleTrie[TxOutputRef, TxOutput],
      SparseMerkleTrie[Hash, ContractState],
      SparseMerkleTrie[Hash, WorldState.CodeRecord]
    ] {
  def updateContractUnsafe(key: Hash, fields: AVector[Val]): IOResult[ImmutableWorldState] = {
    for {
      oldState <- getContractState(key)
      newState <- updateContract(key, oldState.updateFieldsUnsafe(fields))
    } yield newState
  }
}
// scalastyle:on

object WorldState {
  val expectedAssetError: IOError = IOError.Serde(SerdeError.validation("Expect AssetOutput"))

  final case class CodeRecord(code: StatefulContract.HalfDecoded, refCount: Int)
  object CodeRecord {
    implicit val serde: Serde[CodeRecord] =
      Serde.forProduct2(CodeRecord.apply, t => (t.code, t.refCount))

    def from(code: StatefulContract.HalfDecoded, recordOpt: Option[CodeRecord]): CodeRecord =
      recordOpt match {
        case Some(record) => record.copy(refCount = record.refCount + 1)
        case None         => CodeRecord(code, 1)
      }
  }

  final case class Persisted(
      outputState: SparseMerkleTrie[TxOutputRef, TxOutput],
      contractState: SparseMerkleTrie[Hash, ContractState],
      codeState: SparseMerkleTrie[Hash, CodeRecord]
  ) extends ImmutableWorldState {
    def getAssetOutputs(
        outputRefPrefix: ByteString,
        maxOutputs: Int,
        predicate: (TxOutputRef, TxOutput) => Boolean
    ): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = {
      outputState
        .getAll(
          outputRefPrefix,
          maxOutputs,
          (outputRef, output) =>
            predicate(outputRef, output) && outputRef.isAssetType && output.isAsset
        )
        .map(_.asUnsafe[(AssetOutputRef, AssetOutput)])
    }

    def getContractOutputs(
        outputRefPrefix: ByteString,
        maxOutputs: Int
    ): IOResult[AVector[(ContractOutputRef, ContractOutput)]] = {
      outputState
        .getAll(
          outputRefPrefix,
          maxOutputs,
          (outputRef, output) => outputRef.isContractType && output.isContract
        )
        .map(_.asUnsafe[(ContractOutputRef, ContractOutput)])
    }

    def getContractStates(): IOResult[AVector[(ContractId, ContractState)]] = {
      contractState.getAll(ByteString.empty, Int.MaxValue)
    }

    def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[Persisted] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState, codeState))
    }

    private[WorldState] def putOutput(
        outputRef: TxOutputRef,
        output: TxOutput
    ): IOResult[Persisted] = {
      outputState.put(outputRef, output).map(Persisted(_, contractState, codeState))
    }

    def createContractUnsafe(
        code: StatefulContract.HalfDecoded,
        fields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Persisted] = {
      val state = ContractState.unsafe(code, fields, outputRef)
      for {
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(outputRef.key, state)
        recordOpt        <- codeState.getOpt(code.hash)
        newCodeState     <- codeState.put(code.hash, CodeRecord.from(code, recordOpt))
      } yield Persisted(newOutputState, newContractState, newCodeState)
    }

    def updateContract(key: Hash, state: ContractState): IOResult[Persisted] = {
      contractState.put(key, state).map(Persisted(outputState, _, codeState))
    }

    def updateContract(
        key: Hash,
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Persisted] = {
      for {
        state            <- getContractState(key)
        newOutputState   <- outputState.put(outputRef, output)
        newContractState <- contractState.put(key, state.updateOutputRef(outputRef))
      } yield Persisted(newOutputState, newContractState, codeState)
    }

    def removeAsset(outputRef: TxOutputRef): IOResult[Persisted] = {
      outputState.remove(outputRef).map(Persisted(_, contractState, codeState))
    }

    def removeContract(contractKey: Hash): IOResult[Persisted] = {
      for {
        state            <- getContractState(contractKey)
        newOutputState   <- outputState.remove(state.contractOutputRef)
        newContractState <- contractState.remove(contractKey)
        codeRecord       <- codeState.get(state.codeHash)
        newCodeState     <- removeContractCode(state, codeRecord)
      } yield Persisted(newOutputState, newContractState, newCodeState)
    }

    def persist(): IOResult[WorldState.Persisted] = Right(this)

    def cached(): WorldState.Cached = {
      val outputStateCache    = CachedSMT.from(outputState)
      val contractOutputCache = CachedSMT.from(contractState)
      val codeStateCache      = CachedSMT.from(codeState)
      Cached(outputStateCache, contractOutputCache, codeStateCache)
    }

    def toHashes: WorldState.Hashes =
      WorldState.Hashes(outputState.rootHash, contractState.rootHash, codeState.rootHash)
  }

  sealed abstract class AbstractCached extends MutableWorldState {
    def outputState: MutableTrie[TxOutputRef, TxOutput, Unit]
    def contractState: MutableTrie[Hash, ContractState, Unit]
    def codeState: MutableTrie[Hash, CodeRecord, Unit]

    def addAsset(outputRef: TxOutputRef, output: TxOutput): IOResult[Unit] = {
      outputState.put(outputRef, output)
    }

    def createContractUnsafe(
        code: StatefulContract.HalfDecoded,
        fields: AVector[Val],
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Unit] = {
      val state = ContractState.unsafe(code, fields, outputRef)
      for {
        _         <- outputState.put(outputRef, output)
        _         <- contractState.put(outputRef.key, state)
        recordOpt <- codeState.getOpt(code.hash)
        _         <- codeState.put(code.hash, CodeRecord.from(code, recordOpt))
      } yield ()
    }

    def updateContract(key: Hash, state: ContractState): IOResult[Unit] = {
      contractState.put(key, state)
    }

    def updateContract(
        key: Hash,
        outputRef: ContractOutputRef,
        output: ContractOutput
    ): IOResult[Unit] = {
      for {
        state <- getContractState(key)
        _     <- outputState.put(outputRef, output)
        _     <- contractState.put(key, state.updateOutputRef(outputRef))
      } yield ()
    }

    def removeAsset(outputRef: TxOutputRef): IOResult[Unit] = {
      outputState.remove(outputRef)
    }

    def removeContract(contractKey: Hash): IOResult[Unit] = {
      for {
        state      <- getContractState(contractKey)
        _          <- outputState.remove(state.contractOutputRef)
        codeRecord <- codeState.get(state.codeHash)
        _          <- removeContractCode(state, codeRecord)
        _          <- contractState.remove(contractKey)
      } yield ()
    }

    def removeContractState(contractId: ContractId): IOResult[Unit] = {
      contractState.remove(contractId)
    }

    // Not supported, use persisted worldstate instead
    def getAssetOutputs(
        outputRefPrefix: ByteString,
        maxOutputs: Int,
        predicate: (TxOutputRef, TxOutput) => Boolean
    ): IOResult[AVector[(AssetOutputRef, AssetOutput)]] = ???
  }

  final case class Cached(
      outputState: CachedSMT[TxOutputRef, TxOutput],
      contractState: CachedSMT[Hash, ContractState],
      codeState: CachedSMT[Hash, CodeRecord]
  ) extends AbstractCached {
    def persist(): IOResult[Persisted] = {
      for {
        outputStateNew   <- outputState.persist()
        contractStateNew <- contractState.persist()
        codeStateNew     <- codeState.persist()
      } yield Persisted(outputStateNew, contractStateNew, codeStateNew)
    }

    def staging(): Staging =
      Staging(outputState.staging(), contractState.staging(), codeState.staging())
  }

  final case class Staging(
      outputState: StagingSMT[TxOutputRef, TxOutput],
      contractState: StagingSMT[Hash, ContractState],
      codeState: StagingSMT[Hash, CodeRecord]
  ) extends AbstractCached {
    def commit(): Unit = {
      outputState.commit()
      contractState.commit()
      codeState.commit()
    }

    def rollback(): Unit = {
      outputState.rollback()
      contractState.rollback()
      codeState.rollback()
    }

    def persist(): IOResult[Persisted] = ??? // should not be called
  }

  def emptyPersisted(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Persisted = {
    val genesisRef  = ContractOutputRef.forSMT
    val emptyOutput = TxOutput.forSMT
    val emptyOutputTrie =
      SparseMerkleTrie.unsafe[TxOutputRef, TxOutput](storage, genesisRef, emptyOutput)
    val emptyState =
      ContractState.unsafe(StatefulContract.forSMT, AVector.empty, genesisRef)
    val emptyCode         = CodeRecord(StatefulContract.forSMT, 0)
    val emptyContractTrie = SparseMerkleTrie.unsafe(storage, Hash.zero, emptyState)
    val emptyCodeTrie     = SparseMerkleTrie.unsafe(storage, Hash.zero, emptyCode)
    Persisted(emptyOutputTrie, emptyContractTrie, emptyCodeTrie)
  }

  def emptyCached(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Cached = {
    emptyPersisted(storage).cached()
  }

  final case class Hashes(outputStateHash: Hash, contractStateHash: Hash, codeStateHash: Hash) {
    def toPersistedWorldState(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Persisted = {
      val outputState   = SparseMerkleTrie[TxOutputRef, TxOutput](outputStateHash, storage)
      val contractState = SparseMerkleTrie[Hash, ContractState](contractStateHash, storage)
      val codeState     = SparseMerkleTrie[Hash, CodeRecord](codeStateHash, storage)
      Persisted(outputState, contractState, codeState)
    }

    def toCachedWorldState(storage: KeyValueStorage[Hash, SparseMerkleTrie.Node]): Cached = {
      toPersistedWorldState(storage).cached()
    }

    def stateHash: Hash = Hash.hash(outputStateHash.bytes ++ contractStateHash.bytes)
  }
  object Hashes {
    implicit val serde: Serde[Hashes] =
      Serde.forProduct3(
        Hashes.apply,
        t => (t.outputStateHash, t.contractStateHash, t.codeStateHash)
      )
  }
}
