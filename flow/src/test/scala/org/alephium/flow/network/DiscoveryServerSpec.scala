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

import java.net.{InetAddress, InetSocketAddress}

import akka.testkit.{SocketUtil, TestProbe}
import org.scalacheck.Gen

import org.alephium.protocol.SignatureSchema
import org.alephium.protocol.config._
import org.alephium.protocol.model.{CliqueId, CliqueInfo, NetworkType, NoIndexModelGenerators}
import org.alephium.util.{AlephiumActorSpec, AVector, Duration}

object DiscoveryServerSpec {
  def createAddr(port: Int): InetSocketAddress =
    new InetSocketAddress(InetAddress.getLocalHost, port)

  def createConfig(groupSize: Int,
                   port: Int,
                   _peersPerGroup: Int,
                   _scanFrequency: Duration  = Duration.unsafe(200),
                   _expireDuration: Duration = Duration.ofHoursUnsafe(1))
    : (InetSocketAddress, DiscoveryConfig with CliqueConfig) = {
    val publicAddress: InetSocketAddress = new InetSocketAddress("localhost", port)
    val discoveryConfig = new DiscoveryConfig with CliqueConfig {
      val (discoveryPrivateKey, discoveryPublicKey) = SignatureSchema.generatePriPub()

      val peersPerGroup: Int          = _peersPerGroup
      val scanMaxPerGroup: Int        = 1
      val scanFrequency: Duration     = _scanFrequency
      val scanFastFrequency: Duration = _scanFrequency
      val neighborsPerGroup: Int      = _peersPerGroup

      override val expireDuration: Duration = _expireDuration

      val groups: Int    = groupSize
      val brokerNum: Int = groupSize
    }
    publicAddress -> discoveryConfig
  }
}

class DiscoveryServerSpec
    extends AlephiumActorSpec("DiscoveryServerSpec")
    with NoIndexModelGenerators {
  import DiscoveryServerSpec._

  def generateCliqueInfo(master: InetSocketAddress, groupConfig: GroupConfig): CliqueInfo = {
    val newInfo = CliqueInfo.unsafe(CliqueId.generate,
                                    AVector(Option(master)),
                                    AVector(master),
                                    groupConfig.groups)
    CliqueInfo.validate(newInfo)(groupConfig).isRight is true
    newInfo.coordinatorAddress is master
    newInfo
  }

  it should "discovery each other for two cliques" in new GroupConfigFixture {
    val groups              = Gen.choose(2, 10).sample.get
    val port0               = SocketUtil.temporaryLocalPort(udp = true)
    val cliqueInfo0         = generateCliqueInfo(createAddr(port0), groupConfig)
    val (address0, config0) = createConfig(groups, port0, 1)
    val port1               = SocketUtil.temporaryLocalPort(udp = true)
    val cliqueInfo1         = generateCliqueInfo(createAddr(port1), groupConfig)
    val (address1, config1) = createConfig(groups, port1, 1)
    val networkConfig       = new NetworkConfig { val networkType = NetworkType.Testnet }

    val server0 =
      system.actorOf(DiscoveryServer.props(address0)(groupConfig, config0, networkConfig),
                     "server0")
    val server1 =
      system.actorOf(DiscoveryServer.props(address1, address0)(groupConfig, config1, networkConfig),
                     "server1")

    server0 ! DiscoveryServer.SendCliqueInfo(cliqueInfo0)
    server1 ! DiscoveryServer.SendCliqueInfo(cliqueInfo1)

    Thread.sleep(1000)

    val probo0 = TestProbe()
    server0.tell(DiscoveryServer.GetNeighborCliques, probo0.ref)
    val probo1 = TestProbe()
    server1.tell(DiscoveryServer.GetNeighborCliques, probo1.ref)

    probo0.expectMsgPF() {
      case DiscoveryServer.NeighborCliques(peers) =>
        peers.length is 1
        peers.head is cliqueInfo1.interCliqueInfo.get
    }
    probo1.expectMsgPF() {
      case DiscoveryServer.NeighborCliques(peers) =>
        peers.length is 1
        peers.head is cliqueInfo0.interCliqueInfo.get
    }
  }
}
