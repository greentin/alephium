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

package org.alephium.wallet

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future, Promise}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import com.typesafe.scalalogging.StrictLogging

import org.alephium.util.Service
import org.alephium.wallet.config.WalletConfig
import org.alephium.wallet.service.WalletService
import org.alephium.wallet.web._

class WalletApp(config: WalletConfig)(implicit actorSystem: ActorSystem,
                                      val executionContext: ExecutionContext)
    extends Service
    with StrictLogging {

  // scalastyle:off magic.number
  val httpClient: HttpClient = HttpClient(512, OverflowStrategy.fail)
  // scalastyle:on magic.number

  val blockFlowClient: BlockFlowClient =
    BlockFlowClient.apply(httpClient,
                          config.blockflow.uri,
                          config.blockflow.groups,
                          config.networkType)

  val walletService: WalletService =
    WalletService.apply(blockFlowClient, config.secretDir, config.networkType)

  val walletServer: WalletServer = new WalletServer(walletService, config.networkType)

  val routes: Route = walletServer.route ~ walletServer.docsRoute

  private val bindingPromise: Promise[Http.ServerBinding] = Promise()

  override val subServices: ArraySeq[Service] = ArraySeq(walletService)

  protected def startSelfOnce(): Future[Unit] = {
    for {
      binding <- Http().bindAndHandle(routes, "localhost", config.port)
    } yield {
      bindingPromise.success(binding)
      logger.info(s"Listening wallet http request on $binding")
    }
  }

  protected def stopSelfOnce(): Future[Unit] =
    for {
      _ <- bindingPromise.future.flatMap(_.unbind())
    } yield {
      logger.info("Wallet stopped")
    }
}
