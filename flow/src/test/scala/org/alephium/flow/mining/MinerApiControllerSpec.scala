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

package org.alephium.flow.mining

import scala.util.Random

import akka.actor.ActorRef
import akka.io.{IO, Tcp}
import akka.testkit.{EventFilter, TestActor, TestProbe}
import org.scalatest.concurrent.{Eventually, IntegrationPatience}

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.handler.{BlockChainHandler, TestUtils, ViewHandler}
import org.alephium.flow.validation.InvalidBlockVersion
import org.alephium.protocol.model.ChainIndex
import org.alephium.serde.serialize
import org.alephium.util.{AVector, SocketUtil}

class MinerApiControllerSpec extends AlephiumFlowActorSpec with SocketUtil {
  trait Fixture extends Eventually with IntegrationPatience {
    val apiPort                         = generatePort()
    val (allHandlers, allHandlerProbes) = TestUtils.createAllHandlersProbe
    val minerApiController = EventFilter.info(start = "Miner API server bound").intercept {
      newTestActorRef[MinerApiController](
        MinerApiController.props(allHandlers)(
          brokerConfig,
          networkConfig.copy(minerApiPort = apiPort),
          miningSetting
        )
      )
    }
    val bindAddress = minerApiController.underlyingActor.apiAddress

    def connectToServer(probe: TestProbe): ActorRef = {
      probe.send(IO(Tcp), Tcp.Connect(bindAddress))
      allHandlerProbes.viewHandler.expectMsg(ViewHandler.Subscribe)
      eventually(minerApiController.underlyingActor.pendings.length is 0)
      probe.expectMsgType[Tcp.Connected]
      val connection = probe.lastSender
      probe.reply(Tcp.Register(probe.ref))
      connection
    }

    val minerAddresses =
      AVector.tabulate(groups0)(g => getGenesisLockupScript(ChainIndex.unsafe(g, 0)))
  }

  trait SyncedFixture extends Fixture {
    allHandlerProbes.viewHandler.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ViewHandler.Subscribe =>
          sender ! ViewHandler.SubscribeResult(succeeded = true)
          TestActor.KeepRunning
      }
    )
  }

  it should "accept new connections" in new SyncedFixture {
    connectToServer(TestProbe())
    eventually(minerApiController.underlyingActor.connections.length is 1)
    connectToServer(TestProbe())
    eventually(minerApiController.underlyingActor.connections.length is 2)
  }

  it should "broadcast new template" in new SyncedFixture {
    val probe0 = TestProbe()
    val probe1 = TestProbe()
    connectToServer(probe0)
    connectToServer(probe1)

    minerApiController ! ViewHandler.NewTemplates(
      ViewHandler.prepareTemplates(blockFlow, minerAddresses).rightValue
    )
    probe0.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value is a[Jobs]
    }
    probe1.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value is a[Jobs]
    }
  }

  it should "handle submission" in new SyncedFixture with Eventually {
    val probe0      = TestProbe()
    val connection0 = connectToServer(probe0)

    val chainIndex = ChainIndex.unsafe(0, 0)
    val block      = emptyBlock(blockFlow, chainIndex)
    val blockBlob  = serialize(block)
    connection0 ! Tcp.Write(ClientMessage.serialize(SubmitBlock(blockBlob)))

    eventually(minerApiController.underlyingActor.submittingBlocks.contains(block.hash))
    allHandlerProbes.blockHandlers(chainIndex).expectMsgType[BlockChainHandler.Validate]

    val succeeded = Random.nextBoolean()
    val feedback = if (succeeded) {
      BlockChainHandler.BlockAdded(block.hash)
    } else {
      BlockChainHandler.InvalidBlock(block.hash, InvalidBlockVersion)
    }
    minerApiController ! feedback
    probe0.expectMsgPF() { case Tcp.Received(data) =>
      ServerMessage.deserialize(data).rightValue.value is SubmitResult(0, 0, succeeded)
    }
  }

  trait ConnectionFixture extends Fixture with Eventually {
    val probe = TestProbe()
    watch(probe.ref)

    probe.send(IO(Tcp), Tcp.Connect(bindAddress))
    eventually(probe.expectMsgType[Tcp.Connected])
    eventually(minerApiController.underlyingActor.pendings.length is 1)
    probe.reply(Tcp.Register(probe.ref))
  }

  it should "close the connection if view handler is not ready" in new ConnectionFixture {
    minerApiController ! ViewHandler.SubscribeResult(false)
    eventually(minerApiController.underlyingActor.pendings.length is 0)
    probe.expectMsgType[Tcp.ErrorClosed]
  }

  it should "close the connection if view handler is not ready after a while" in new ConnectionFixture {
    minerApiController ! ViewHandler.SubscribeResult(true)
    eventually(minerApiController.underlyingActor.pendings.length is 0)
    eventually(minerApiController.underlyingActor.connections.length is 1)

    minerApiController ! ViewHandler.SubscribeResult(false)
    probe.expectMsgType[Tcp.ErrorClosed]
    eventually(minerApiController.underlyingActor.connections.length is 0)
  }
}
