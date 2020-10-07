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

package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.alephium.flow.setting.{AlephiumConfig, Platform}
import org.alephium.flow.validation.Validation
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.util.{AVector, Random}

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
class MiningBench {

  val config: AlephiumConfig            = AlephiumConfig.load(Platform.getRootPath()).toOption.get
  implicit val groupConfig: GroupConfig = config.broker

  @Benchmark
  def mineGenesis(): Boolean = {
    val nonce = Random.source.nextInt()
    val block = Block.genesis(AVector.empty, config.consensus.maxMiningTarget, BigInt(nonce))
    val i     = Random.source.nextInt(groupConfig.groups)
    val j     = Random.source.nextInt(groupConfig.groups)
    Validation.validateMined(block, ChainIndex.unsafe(i, j))
  }
}
