package org.alephium.appserver

import scala.concurrent._

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.client.{FairMiner, Miner}
import org.alephium.flow.core.{BlockFlow, FlowHandler, MultiChain, TxHandler}
import org.alephium.flow.core.FlowHandler.BlockNotify
import org.alephium.flow.model.DataOrigin
import org.alephium.flow.network.DiscoveryServer
import org.alephium.flow.platform.{Mode, PlatformProfile}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, GroupIndex, Transaction}
import org.alephium.protocol.script.PubScript
import org.alephium.rpc.{CORSHandler, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC._
import org.alephium.rpc.model.JsonRPC.Response
import org.alephium.util.{EventBus, Hex, TimeStamp}

class RPCServer(mode: Mode) extends RPCServerAbstract {
  import RPCServer._

  implicit val system: ActorSystem                = mode.node.system
  implicit val materializer: ActorMaterializer    = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val config: PlatformProfile            = mode.profile
  implicit val rpcConfig: RPCConfig               = RPCConfig.load(config.aleph)
  implicit val askTimeout: Timeout                = Timeout(rpcConfig.askTimeout.asScala)

  def doBlockflowFetch(req: Request): FutureTry[FetchResponse] =
    Future.successful(blockflowFetch(mode.node.blockFlow, req))

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def doGetPeers(req: Request): FutureTry[PeersResult] =
    mode.node.discoveryServer.ask(DiscoveryServer.GetPeerCliques).map { result =>
      val peers = result.asInstanceOf[DiscoveryServer.PeerCliques].peers
      Right(PeersResult(peers))
    }

  def doBlockNotify(blockNotify: BlockNotify): Json =
    blockNotifyEncoder(mode.node.blockFlow).apply(blockNotify)

  def doGetBalance(req: Request): FutureTry[Balance] =
    Future.successful(getBalance(mode.node.blockFlow, req))

  def doTransfer(req: Request): FutureTry[TransferResult] = {
    val txHandler = mode.node.allHandlers.txHandler
    Future.successful(transfer(mode.node.blockFlow, txHandler, req))
  }

  def runServer(): Future[Unit] = {
    val miner = {
      val props = FairMiner.props(mode.node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    Http()
      .bindAndHandle(routeHttp(miner), rpcConfig.networkInterface.getHostAddress, mode.rpcHttpPort)
      .map(_ => ())
    Http()
      .bindAndHandle(routeWs(mode.node.eventBus),
                     rpcConfig.networkInterface.getHostAddress,
                     mode.rpcWsPort)
      .map(_ => ())
  }
}

trait RPCServerAbstract extends StrictLogging {
  import RPCServer._

  implicit def system: ActorSystem
  implicit def materializer: ActorMaterializer
  implicit def executionContext: ExecutionContext
  implicit def config: PlatformProfile
  implicit def rpcConfig: RPCConfig
  implicit def askTimeout: Timeout

  def doBlockNotify(blockNotify: BlockNotify): Json
  def doBlockflowFetch(req: Request): FutureTry[FetchResponse]
  def doGetPeers(req: Request): FutureTry[PeersResult]
  def doGetBalance(req: Request): FutureTry[Balance]
  def doTransfer(req: Request): FutureTry[TransferResult]
  def doStartMining(miner: ActorRef): FutureTry[Boolean] =
    execute(miner ! Miner.Start)
  def doStopMining(miner: ActorRef): FutureTry[Boolean] =
    execute(miner ! Miner.Stop)

  def runServer(): Future[Unit]

  def handleEvent(event: EventBus.Event): TextMessage = {
    event match {
      case bn @ FlowHandler.BlockNotify(_, _) =>
        val params = doBlockNotify(bn)
        val notif  = Notification("block_notify", params)
        TextMessage(notif.asJson.noSpaces)
    }
  }

  def handlerRPC(miner: ActorRef): Handler = Map.apply(
    "blockflow_fetch" -> (req => wrap(req, doBlockflowFetch(req))),
    "clique_info"     -> (req => wrap(req, doGetPeers(req))),
    "get_balance"     -> (req => wrap(req, doGetBalance(req))),
    "transfer"        -> (req => wrap(req, doTransfer(req))),
    "mining_start"    -> (req => wrap(req, doStartMining(miner))),
    "mining_stop"     -> (req => wrap(req, doStopMining(miner)))
  )

  def routeHttp(miner: ActorRef): Route =
    CORSHandler(JsonRPCHandler.routeHttp(handlerRPC(miner)))

  def routeWs(eventBus: ActorRef): Route = {
    path("events") {
      CORSHandler(get {
        extractUpgradeToWebSocket { upgrade =>
          val (actor, source) =
            Source.actorRef(bufferSize, OverflowStrategy.fail).preMaterialize()
          eventBus.tell(EventBus.Subscribe, actor)
          val response = upgrade.handleMessages(wsFlow(eventBus, actor, source))
          complete(response)
        }
      })
    }
  }

  def wsFlow(eventBus: ActorRef,
             actor: ActorRef,
             source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
    Flow
      .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
      .watchTermination() { (_, termination) =>
        termination.onComplete(_ => eventBus.tell(EventBus.Unsubscribe, actor))
      }
  }
}

object RPCServer extends StrictLogging {
  import Response.Failure
  type Try[T]       = Either[Failure, T]
  type FutureTry[T] = Future[Try[T]]

  val bufferSize: Int = 64

  def withReq[T: Decoder, R](req: Request)(f: T => R): Try[R] = {
    req.paramsAs[T] match {
      case Right(query)  => Right(f(query))
      case Left(failure) => Left(failure)
    }
  }

  def withReqF[T: Decoder, R](req: Request)(f: T => Try[R]): Try[R] = {
    req.paramsAs[T] match {
      case Right(query)  => f(query)
      case Left(failure) => Left(failure)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def blockflowFetch(blockFlow: BlockFlow, req: Request)(
      implicit rpc: RPCConfig,
      cfg: ConsensusConfig): Try[FetchResponse] = {
    withReq[FetchRequest, FetchResponse](req) { query =>
      val now        = TimeStamp.now()
      val lowerBound = (now - rpc.blockflowFetchMaxAge).get // Note: get should be safe
      val from = query.from match {
        case Some(ts) => if (ts > lowerBound) ts else lowerBound
        case None     => lowerBound
      }

      val headers = blockFlow.getHeadersUnsafe(header => header.timestamp > from)
      FetchResponse(headers.map(wrapBlockHeader(blockFlow, _)))
    }
  }

  def getBalance(blockFlow: BlockFlow, req: Request): Try[Balance] = {
    withReqF[GetBalance, Balance](req) { query =>
      if (query.`type` == GetBalance.pkh) {
        val result = for {
          address <- decodeAddress(query.address)
          _       <- checkGroup(blockFlow, address)
          balance <- getP2pkhBalance(blockFlow, address)
        } yield balance
        result match {
          case Right(balance) => Right(balance)
          case Left(error)    => Left(error)
        }
      } else {
        Left(Response.failed(s"Invalid address type ${query.`type`}"))
      }
    }
  }

  def decodeAddress(raw: String): Try[ED25519PublicKey] = {
    val addressOpt = for {
      bytes   <- Hex.from(raw)
      address <- ED25519PublicKey.from(bytes)
    } yield address

    addressOpt match {
      case Some(address) => Right(address)
      case None          => Left(Response.failed("Failed in decoding address"))
    }
  }

  def decodePublicKey(raw: String): Try[ED25519PublicKey] =
    decodeRandomBytes(raw, ED25519PublicKey.from, "public key")

  def decodePrivateKey(raw: String): Try[ED25519PrivateKey] =
    decodeRandomBytes(raw, ED25519PrivateKey.from, "private key")

  def decodeRandomBytes[T](raw: String, from: ByteString => Option[T], name: String): Try[T] = {
    val addressOpt = for {
      bytes   <- Hex.from(raw)
      address <- from(bytes)
    } yield address

    addressOpt match {
      case Some(address) => Right(address)
      case None          => Left(Response.failed(s"Failed in decoding $name"))
    }
  }

  def getP2pkhBalance(blockFlow: BlockFlow, address: ED25519PublicKey): Try[Balance] = {
    blockFlow.getP2pkhUtxos(address) match {
      case Right(utxos) => Right(Balance(utxos.sumBy(_._2.value), utxos.length))
      case Left(_)      => failedInIO
    }
  }

  def transfer(blockFlow: BlockFlow, txHandler: ActorRef, req: Request): Try[TransferResult] = {
    withReqF[Transfer, TransferResult](req) { query =>
      if (query.fromType == GetBalance.pkh || query.toType == GetBalance.pkh) {
        val result = for {
          fromAddress    <- decodePublicKey(query.fromAddress)
          _              <- checkGroup(blockFlow, fromAddress)
          toAddress      <- decodePublicKey(query.toAddress)
          fromPrivateKey <- decodePrivateKey(query.fromPrivateKey)
          tx             <- prepareTransaction(blockFlow, fromAddress, toAddress, query.value, fromPrivateKey)
        } yield {
          // publish transaction
          txHandler ! TxHandler.AddTx(tx, DataOrigin.Local)
          TransferResult(Hex.toHexString(tx.hash.bytes))
        }
        result match {
          case Right(result) => Right(result)
          case Left(error)   => Left(error)
        }
      } else {
        Left(Response.failed(s"Invalid address types: ${query.fromType} or ${query.toType}"))
      }
    }
  }

  def prepareTransaction(blockFlow: BlockFlow,
                         fromAddress: ED25519PublicKey,
                         toAddress: ED25519PublicKey,
                         value: BigInt,
                         fromPrivateKey: ED25519PrivateKey): Try[Transaction] = {
    blockFlow.prepareP2pkhTx(fromAddress, toAddress, value, fromPrivateKey) match {
      case Right(Some(transaction)) => Right(transaction)
      case Right(None)              => Left(Response.failed("Not enough balance"))
      case Left(_)                  => failedInIO
    }
  }

  def checkGroup(blockFlow: BlockFlow, address: ED25519PublicKey): Try[Unit] = {
    val pubScript  = PubScript.p2pkh(address)
    val groupIndex = GroupIndex.from(pubScript)(blockFlow.config)
    if (blockFlow.config.brokerInfo.contains(groupIndex)) Right(())
    else Left(Response.failed(s"Address ${address.shortHex} belongs to other groups"))
  }

  def wrapBlockHeader(chain: MultiChain, header: BlockHeader)(
      implicit config: ConsensusConfig): FetchEntry = {
    val index = header.chainIndex

    FetchEntry(
      hash      = header.shortHex,
      timestamp = header.timestamp,
      chainFrom = index.from.value,
      chainTo   = index.to.value,
      height    = chain.getHeight(header),
      deps      = header.blockDeps.toIterable.map(_.shortHex).toList
    )
  }

  def execute(f: => Unit)(implicit ec: ExecutionContext): FutureTry[Boolean] =
    Future {
      f
      Right(true)
    }

  def wrap[T: Encoder](req: Request, result: FutureTry[T])(
      implicit ec: ExecutionContext): Future[Response] = result.map {
    case Right(t)    => Response.successful(req, t)
    case Left(error) => error
  }

  def failedInIO[T]: Try[T] = Left(Response.failed("Failed in IO"))

  def fetchEntryEncode(header: BlockHeader, height: Int)(implicit config: ConsensusConfig): Json =
    FetchEntry(
      hash      = header.shortHex,
      timestamp = header.timestamp,
      chainFrom = header.chainIndex.from.value,
      chainTo   = header.chainIndex.to.value,
      height    = height,
      deps      = header.blockDeps.toIterable.map(_.shortHex).toList
    ).asJson

  def blockNotifyEncode(bn: BlockNotify, height: Int)(implicit config: ConsensusConfig): Json =
    Json.obj(("header", fetchEntryEncode(bn.header, height)), ("height", Json.fromInt(height)))

  def blockNotifyEncoder(chain: MultiChain)(implicit cfg: ConsensusConfig): Encoder[BlockNotify] =
    new Encoder[BlockNotify] {
      final def apply(bn: BlockNotify): Json = blockNotifyEncode(bn, chain.getHeight(bn.header))
    }
}
