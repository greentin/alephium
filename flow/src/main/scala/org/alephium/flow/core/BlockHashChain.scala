package org.alephium.flow.core

import scala.annotation.tailrec

import org.alephium.flow.core.BlockHashChain.ChainDiff
import org.alephium.flow.io.{BlockStateStorage, HeightIndexStorage, IOError, IOResult}
import org.alephium.flow.model.BlockState
import org.alephium.flow.platform.PlatformConfig
import org.alephium.protocol.ALF
import org.alephium.protocol.ALF.Hash
import org.alephium.util.{AVector, EitherF, TimeStamp}

// scalastyle:off number.of.methods
trait BlockHashChain extends BlockHashPool with ChainDifficultyAdjustment with HashTreeTipsHolder {
  implicit def config: PlatformConfig

  def genesisHash: Hash

  def isGenesis(hash: Hash): Boolean = hash == genesisHash

  @volatile var numHashes: Int = 0

  def blockStateStorage: BlockStateStorage

  def heightIndexStorage: HeightIndexStorage

  protected def addHash(hash: Hash,
                        parentHash: Hash,
                        height: Int,
                        weight: BigInt,
                        chainWeight: BigInt,
                        timestamp: TimeStamp): IOResult[Unit] = {
    for {
      _ <- blockStateStorage.put(hash, BlockState(height, weight, chainWeight))
      _ <- updateHeightIndex(hash, height)
      _ <- addNewTip(hash, timestamp, parentHash)
    } yield {
      numHashes += 1
    }
  }

  protected def addGenesis(hash: Hash): IOResult[Unit] = {
    assume(hash == genesisHash)
    for {
      _ <- blockStateStorage.put(
        genesisHash,
        BlockState(ALF.GenesisHeight, ALF.GenesisWeight, ALF.GenesisWeight))
      _ <- updateHeightIndex(genesisHash, ALF.GenesisHeight)
      _ <- addGenesisTip(genesisHash, ALF.GenesisTimestamp)
    } yield {
      numHashes += 1
    }
  }

  @inline
  private def updateHeightIndex(hash: Hash, height: Int): IOResult[Unit] = {
    heightIndexStorage.getOpt(height).flatMap {
      case Some(hashes) => heightIndexStorage.put(height, hashes :+ hash)
      case None         => heightIndexStorage.put(height, AVector(hash))
    }
  }

  def getParentHash(hash: Hash): IOResult[Hash]

  def maxWeight: IOResult[BigInt] = EitherF.foldTry(tips.keys, BigInt(0)) { (weight, hash) =>
    getWeight(hash).map(weight.max)
  }

  def maxHeight: IOResult[Int] = EitherF.foldTry(tips.keys, 0) { (height, hash) =>
    getHeight(hash).map(math.max(height, _))
  }

  def contains(hash: Hash): IOResult[Boolean]      = blockStateStorage.exists(hash)
  def containsUnsafe(hash: Hash): Boolean          = blockStateStorage.existsUnsafe(hash)
  def getState(hash: Hash): IOResult[BlockState]   = blockStateStorage.get(hash)
  def getStateUnsafe(hash: Hash): BlockState       = blockStateStorage.getUnsafe(hash)
  def getHeight(hash: Hash): IOResult[Int]         = blockStateStorage.get(hash).map(_.height)
  def getHeightUnsafe(hash: Hash): Int             = blockStateStorage.getUnsafe(hash).height
  def getWeight(hash: Hash): IOResult[BigInt]      = blockStateStorage.get(hash).map(_.weight)
  def getWeightUnsafe(hash: Hash): BigInt          = blockStateStorage.getUnsafe(hash).weight
  def getChainWeight(hash: Hash): IOResult[BigInt] = blockStateStorage.get(hash).map(_.chainWeight)
  def getChainWeightUnsafe(hash: Hash): BigInt     = blockStateStorage.getUnsafe(hash).chainWeight

  def isTip(hash: Hash): Boolean = tips.contains(hash)

  def getHashes(height: Int): IOResult[AVector[Hash]] = {
    heightIndexStorage.getOpt(height).map {
      case Some(hashes) => hashes
      case None         => AVector.empty
    }
  }

  def getBestTipUnsafe: Hash = {
    assert(tips.size != 0)
    val weighted = getAllTips.map { hash =>
      hash -> getWeightUnsafe(hash)
    }
    weighted.maxBy(_._2)._1
  }

  def getAllTips: AVector[Hash] = {
    AVector.from(tips.keys)
  }

  // TODO: optimize this, very inefficient
  def getAllBlockHashes: IOResult[AVector[Hash]] = {
    getHashesAfter(genesisHash)
  }

  private def getLink(hash: Hash): IOResult[BlockHashChain.Link] = {
    getParentHash(hash).map(BlockHashChain.Link(_, hash))
  }

  def getHashesAfter(locator: Hash): IOResult[AVector[Hash]] = {
    for {
      height <- getHeight(locator)
      hashes <- getHashes(height + 1)
      links  <- hashes.mapE(getLink)
      all    <- getHashesAfter(height + 1, links.filter(_.parentHash == locator).map(_.hash))
    } yield all
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def getHashesAfter(height: Int, hashes: AVector[Hash]): IOResult[AVector[Hash]] = {
    if (hashes.isEmpty) Right(AVector.empty)
    else {
      for {
        childHashes <- getHashes(height + 1)
        childPairs  <- childHashes.mapE(getLink)
        validChildHashes = childPairs.filter(p => hashes.contains(p.parentHash)).map(_.hash)
        rest <- getHashesAfter(height + 1, validChildHashes)
      } yield hashes ++ rest
    }
  }

  def getPredecessor(hash: Hash, height: Int): IOResult[Hash] = {
    assume(height >= ALF.GenesisHeight)
    @tailrec
    def iter(currentHash: Hash, currentHeight: Int): IOResult[Hash] = {
      if (currentHeight == height) Right(currentHash)
      else {
        getParentHash(currentHash) match {
          case Right(parentHash) => iter(parentHash, currentHeight - 1)
          case Left(error)       => Left(error)
        }
      }
    }

    getHeight(hash).flatMap(iter(hash, _))
  }

  // If oldHash is an ancestor of newHash, it returns all the new hashes after oldHash to newHash (inclusive)
  def getBlockHashesBetween(newHash: Hash, oldHash: Hash): IOResult[AVector[Hash]] = {
    getHeight(oldHash).flatMap(getBlockHashesBetween(newHash, oldHash, _))
  }

  def getBlockHashesBetween(newHash: Hash,
                            oldHash: Hash,
                            oldHeight: Int): IOResult[AVector[Hash]] = {
    assume(oldHeight >= ALF.GenesisHeight)
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def iter(acc: AVector[Hash], current: Hash): IOResult[AVector[Hash]] = {
      getHeight(current).flatMap { currentHeight =>
        if (currentHeight == oldHeight) {
          if (current == oldHash) Right(acc)
          else {
            val error = new RuntimeException(
              s"Cannot calculate the hashes between new ${newHash.shortHex} and old ${oldHash.shortHex}")
            Left(IOError.Other(error))
          }
        } else {
          getParentHash(current).flatMap(iter(acc :+ current, _))
        }
      }
    }

    iter(AVector.empty, newHash).map(_.reverse)
  }

  def getBlockHashSlice(hash: Hash): IOResult[AVector[Hash]] = {
    @tailrec
    def iter(acc: AVector[Hash], current: Hash): IOResult[AVector[Hash]] = {
      if (isGenesis(current)) Right(acc :+ current)
      else {
        getParentHash(current) match {
          case Right(parentHash) => iter(acc :+ current, parentHash)
          case Left(error)       => Left(error)
        }
      }
    }

    iter(AVector.empty, hash).map(_.reverse)
  }

  def isBefore(hash1: Hash, hash2: Hash): IOResult[Boolean] = {
    for {
      height1 <- getHeight(hash1)
      height2 <- getHeight(hash2)
      result  <- isBefore(hash1, height1, hash2, height2)
    } yield result
  }

  private def isBefore(hash1: Hash, height1: Int, hash2: Hash, height2: Int): IOResult[Boolean] = {
    if (height1 < height2) {
      getPredecessor(hash2, height1).map(_.equals(hash1))
    } else if (height1 == height2) {
      Right(hash1.equals(hash2))
    } else Right(false)
  }

  def calHashDiff(newHash: Hash, oldHash: Hash): IOResult[ChainDiff] = {
    for {
      newHeight <- getHeight(newHash)
      oldHeight <- getHeight(oldHash)
      heightUntil = math.min(newHeight, oldHeight) - 1 // h - 1 to include earlier one
      newBack <- chainBack(newHash, heightUntil)
      oldBack <- chainBack(oldHash, heightUntil)
      diff    <- calHashDiffFromSameHeight(newBack.head, oldBack.head)
    } yield {
      ChainDiff(oldBack.tail.reverse ++ diff.toRemove.reverse, diff.toAdd ++ newBack.tail)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def calHashDiffFromSameHeight(newHash: ALF.Hash,
                                        oldHash: ALF.Hash): IOResult[ChainDiff] = {
    if (newHash == oldHash) Right(ChainDiff(AVector.empty, AVector.empty))
    else {
      for {
        newParent <- getParentHash(newHash)
        oldParent <- getParentHash(oldHash)
        diff      <- calHashDiffFromSameHeight(newParent, oldParent)
      } yield ChainDiff(diff.toRemove :+ oldHash, diff.toAdd :+ newHash)
    }
  }
}
// scalastyle:on number.of.methods

object BlockHashChain {
  final case class ChainDiff(toRemove: AVector[Hash], toAdd: AVector[Hash])

  final case class Link(parentHash: Hash, hash: Hash)
}
