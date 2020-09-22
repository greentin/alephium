package org.alephium.flow.validation

import org.alephium.flow.core.{BlockFlow, BlockHeaderChain}
import org.alephium.io.IOResult
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig}
import org.alephium.protocol.model.BlockHeader
import org.alephium.util.TimeStamp

trait HeaderValidation extends Validation[BlockHeader, HeaderStatus] {
  import ValidationStatus._

  def validate(header: BlockHeader, flow: BlockFlow): IOResult[HeaderStatus] = {
    convert(checkHeader(header, flow), ValidHeader)
  }

  protected[validation] def checkHeader(header: BlockHeader,
                                        flow: BlockFlow): HeaderValidationResult[Unit] = {
    for {
      _ <- checkHeaderUntilDependencies(header, flow)
      _ <- checkHeaderAfterDependencies(header, flow)
    } yield ()
  }

  def validateUntilDependencies(header: BlockHeader, flow: BlockFlow): IOResult[HeaderStatus] = {
    convert(checkHeaderUntilDependencies(header, flow), ValidHeader)
  }

  def validateAfterDependencies(header: BlockHeader, flow: BlockFlow): IOResult[HeaderStatus] = {
    convert(checkHeaderAfterDependencies(header, flow), ValidHeader)
  }

  protected[validation] def checkHeaderUntilDependencies(
      header: BlockHeader,
      flow: BlockFlow): HeaderValidationResult[Unit] = {
    for {
      parent <- getParentHeader(flow, header)
      _      <- checkTimeStampIncreasing(header, parent)
      _      <- checkTimeStampDrift(header)
      _      <- checkWorkAmount(header)
      _      <- checkDependencies(header, flow)
    } yield ()
  }

  protected[validation] def checkHeaderAfterDependencies(
      header: BlockHeader,
      flow: BlockFlow): HeaderValidationResult[Unit] = {
    val headerChain = flow.getHeaderChain(header)
    for {
      _ <- checkWorkTarget(header, headerChain)
      _ <- checkFlow(header, flow)
    } yield ()
  }

  protected[validation] def getParentHeader(
      blockFlow: BlockFlow,
      header: BlockHeader): HeaderValidationResult[BlockHeader]

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkGenesisTimeStamp(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisDependencies(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisWorkAmount(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkGenesisWorkTarget(header: BlockHeader): HeaderValidationResult[Unit]

  protected[validation] def checkTimeStampIncreasing(header: BlockHeader, parent: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkTimeStampDrift(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkWorkAmount(header: BlockHeader): HeaderValidationResult[Unit]
  protected[validation] def checkDependencies(header: BlockHeader, flow: BlockFlow): HeaderValidationResult[Unit]
  protected[validation] def checkWorkTarget(header: BlockHeader, headerChain: BlockHeaderChain): HeaderValidationResult[Unit]
  protected[validation] def checkFlow(header: BlockHeader, flow: BlockFlow)(implicit brokerConfig: BrokerConfig): HeaderValidationResult[Unit]
  // format: on
}

object HeaderValidation {
  import ValidationStatus._

  def build(implicit brokerConfig: BrokerConfig,
            consensusConfig: ConsensusConfig): HeaderValidation = new Impl()

  final class Impl(implicit val brokerConfig: BrokerConfig, val consensusConfig: ConsensusConfig)
      extends HeaderValidation {
    protected[validation] def checkGenesisTimeStamp(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.timestamp != TimeStamp.zero) invalidHeader(InvalidGenesisTimeStamp)
      else validHeader(())
    }
    protected[validation] def checkGenesisDependencies(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.blockDeps.nonEmpty) invalidHeader(InvalidGenesisDeps)
      else validHeader(())
    }
    protected[validation] def checkGenesisWorkAmount(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      validHeader(()) // TODO: validate this when initial target is configurable
    }
    protected[validation] def checkGenesisWorkTarget(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.target != consensusConfig.maxMiningTarget) invalidHeader(InvalidGenesisWorkTarget)
      else validHeader(())
    }

    protected[validation] def getParentHeader(
        blockFlow: BlockFlow,
        header: BlockHeader): HeaderValidationResult[BlockHeader] = {
      ValidationStatus.from(blockFlow.getBlockHeader(header.parentHash))
    }

    protected[validation] def checkTimeStampIncreasing(
        header: BlockHeader,
        parent: BlockHeader): HeaderValidationResult[Unit] = {
      if (header.timestamp <= parent.timestamp) invalidHeader(NoIncreasingTimeStamp)
      else validHeader(())
    }
    protected[validation] def checkTimeStampDrift(
        header: BlockHeader): HeaderValidationResult[Unit] = {
      if (TimeStamp.now() + consensusConfig.maxHeaderTimeStampDrift <= header.timestamp)
        invalidHeader(TooAdvancedTimeStamp)
      else validHeader(())
    }

    protected[validation] def checkWorkAmount(header: BlockHeader): HeaderValidationResult[Unit] = {
      if (Validation.checkWorkAmount(header)) validHeader(()) else invalidHeader(InvalidWorkAmount)
    }

    // TODO: check algorithm validatity of dependencies
    protected[validation] def checkDependencies(header: BlockHeader,
                                                flow: BlockFlow): HeaderValidationResult[Unit] = {
      ValidationStatus.from(header.blockDeps.filterNotE(flow.contains)).flatMap { missings =>
        if (missings.isEmpty) validHeader(()) else invalidHeader(MissingDeps(missings))
      }
    }

    protected[validation] def checkWorkTarget(
        header: BlockHeader,
        headerChain: BlockHeaderChain): HeaderValidationResult[Unit] = {
      ValidationStatus.from(headerChain.getHashTarget(header.parentHash)).flatMap { target =>
        if (target == header.target) validHeader(()) else invalidHeader(InvalidWorkTarget)
      }
    }

    protected[validation] def checkFlow(header: BlockHeader, flow: BlockFlow)(
        implicit brokerConfig: BrokerConfig): HeaderValidationResult[Unit] = {
      if (!brokerConfig.contains(header.chainIndex.from)) {
        ValidationStatus.from(flow.checkFlowHeader(header)).flatMap { ok =>
          if (ok) validHeader(()) else invalidHeader(InvalidHeaderFlow)
        }
      } else {
        validHeader(())
      }
    }
  }
}
