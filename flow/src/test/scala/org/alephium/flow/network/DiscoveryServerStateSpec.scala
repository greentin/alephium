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

package org.alephium.flow.network

import java.net.InetSocketAddress

import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

import akka.event.LoggingAdapter
import akka.io.Udp
import akka.testkit.{SocketUtil, TestProbe}
import org.scalacheck.Gen
import org.scalatest.Assertion

import org.alephium.protocol.config.{CliqueConfig, DiscoveryConfig, GroupConfig, NetworkConfig}
import org.alephium.protocol.message.DiscoveryMessage
import org.alephium.protocol.model._
import org.alephium.util.{ActorRefT, AlephiumActorSpec, AVector, Duration}

class DiscoveryServerStateSpec
    extends AlephiumActorSpec("DiscoveryServer")
    with NoIndexModelGenerators {
  import DiscoveryMessage._
  import DiscoveryServerSpec._

  trait Fixture { self =>
    def groupSize: Int           = 3
    val udpPort: Int             = SocketUtil.temporaryLocalPort(udp = true)
    def peersPerGroup: Int       = 1
    def scanFrequency: Duration  = Duration.unsafe(500)
    def expireDuration: Duration = Duration.ofHoursUnsafe(1)
    val socketProbe              = TestProbe()
    val networkConfig            = new NetworkConfig { val networkType = NetworkType.Testnet }

    implicit lazy val config: DiscoveryConfig with CliqueConfig =
      createConfig(groupSize, udpPort, peersPerGroup, scanFrequency, expireDuration)._2

    val state = new DiscoveryServerState {
      implicit def groupConfig: GroupConfig         = self.config
      implicit def discoveryConfig: DiscoveryConfig = self.config
      implicit def networkConfig: NetworkConfig     = self.networkConfig
      def log: LoggingAdapter                       = system.log

      def bootstrap: ArraySeq[InetSocketAddress] = ArraySeq.empty

      override def publishNewClique(cliqueInfo: InterCliqueInfo): Unit = ()

      lazy val peers = AVector.tabulate(config.brokerNum)(_ => socketAddressGen.sample.get)
      lazy val selfCliqueInfo: CliqueInfo =
        CliqueInfo.unsafe(CliqueId.generate,
                          peers.map(Option.apply),
                          peers,
                          config.groupNumPerBroker)

      setSocket(ActorRefT[Udp.Command](socketProbe.ref))
    }
    val peerClique: CliqueInfo = cliqueInfoGen.sample.get

    def expectPayload[T <: DiscoveryMessage.Payload: ClassTag]: Assertion = {
      val peerConfig =
        createConfig(groupSize, udpPort, peersPerGroup, scanFrequency)._2
      socketProbe.expectMsgPF() {
        case send: Udp.Send =>
          val message =
            DiscoveryMessage
              .deserialize(CliqueId.generate, send.payload, networkConfig.networkType)(peerConfig,
                                                                                       peerConfig)
              .toOption
              .get
          message.payload is a[T]
      }
    }

    def addToTable(cliqueInfo: CliqueInfo): Assertion = {
      state.tryPing(cliqueInfo.interCliqueInfo.get)
      state.isPending(cliqueInfo.id) is true
      state.handlePong(cliqueInfo.interCliqueInfo.get)
      state.isInTable(cliqueInfo.id) is true
    }
  }

  it should "add peer into pending list when just pinged the peer" in new Fixture {
    state.getActivePeers.length is 0
    state.isUnknown(peerClique.id) is true
    state.isPending(peerClique.id) is false
    state.isPendingAvailable is true
    state.tryPing(peerClique.interCliqueInfo.get)
    expectPayload[Ping]
    state.isUnknown(peerClique.id) is false
    state.isPending(peerClique.id) is true
  }

  trait PingedFixture extends Fixture {
    state.tryPing(peerClique.interCliqueInfo.get)
    expectPayload[Ping]
    state.isInTable(peerClique.id) is false
  }

  it should "remove peer from pending list when received pong back" in new PingedFixture {
    state.handlePong(peerClique.interCliqueInfo.get)
    expectPayload[FindNode]
    state.isUnknown(peerClique.id) is false
    state.isPending(peerClique.id) is false
    state.isInTable(peerClique.id) is true
  }

  it should "clean up everything if timeout is zero" in new Fixture {
    override def scanFrequency: Duration  = Duration.unsafe(0)
    override def expireDuration: Duration = Duration.unsafe(0)

    addToTable(peerClique)
    val peer0 = cliqueInfoGen.sample.get
    state.tryPing(peer0.interCliqueInfo.get)
    state.isPending(peer0.id) is true
    state.cleanup()
    state.isInTable(peerClique.id) is false
    state.isPending(peer0.id) is false
  }

  // TODO: use scalacheck
  it should "sort neighbors with respect to target" in new Fixture {
    override def peersPerGroup: Int = 4

    state.getActivePeers.length is 0
    val toAdds = Gen.listOfN(peersPerGroup - 1, cliqueInfoGen).sample.get
    toAdds.foreach(addToTable)

    val peers0 = state.getNeighbors(peerClique.id)
    peers0.length is peersPerGroup
    val bucket0 =
      peers0.map(p => peerClique.id.hammingDist(p.id)).toIterable.toList
    bucket0 is bucket0.sorted
  }
}
