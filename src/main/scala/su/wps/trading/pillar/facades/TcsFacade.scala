package su.wps.trading.pillar.facades

import cats.Monad
import cats.effect.{Async, Resource, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import fs2.grpc.syntax.all._
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import ru.tinkoff.piapi.contract.v1.instruments.{
  InstrumentStatus,
  InstrumentsRequest,
  InstrumentsServiceFs2Grpc
}
import ru.tinkoff.piapi.contract.v1.operations.{OperationsServiceFs2Grpc, PortfolioRequest}
import su.wps.trading.pillar.models.domain.Portfolio
import su.wps.trading.pillar.models.domain.Portfolio.Position
import su.wps.trading.pillar.models.tcs
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}

trait TcsFacade[F[_]] {
  def portfolio: F[Portfolio]
}

object TcsFacade {

  private val apiHost = "invest-public-api.tinkoff.ru"
  private val apiPort = 443

  def resource[I[_]: Sync, F[_]: Async](
    brokerAccountId: tcs.BrokerAccountId,
    token: tcs.Token
  )(implicit logs: Logs[I, F], liftFI: Lift[F, I]): Resource[I, TcsFacade[F]] = {
    val channelBuilder = NettyChannelBuilder
      .forAddress(apiHost, apiPort)
      .useTransportSecurity()

    for {
      channel <- channelBuilder.resource[I]
      instrumentsGrpc <- InstrumentsServiceFs2Grpc.stubResource[F](channel).mapK(liftFI.liftF)
      operationsGrpc <- OperationsServiceFs2Grpc.stubResource[F](channel).mapK(liftFI.liftF)
      implicit0(log: Logging[F]) <- Resource.eval(logs.forService[TcsFacade[F]])
    } yield new Impl[F](brokerAccountId, token, instrumentsGrpc, operationsGrpc)
  }

  private final class Impl[F[_]: Monad: Logging](
    brokerAccountId: tcs.BrokerAccountId,
    token: tcs.Token,
    instrumentsGrpc: InstrumentsServiceFs2Grpc[F, Metadata],
    operationsGrpc: OperationsServiceFs2Grpc[F, Metadata]
  ) extends TcsFacade[F] {

    private val metadata = {
      val md = new Metadata()
      val authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER)
      md.put(authKey, s"Bearer ${token.show}")
      md
    }

    def portfolio: F[Portfolio] = {
      val instrumentsRequest = InstrumentsRequest(InstrumentStatus.INSTRUMENT_STATUS_ALL)
      for {
        tcsCurrencies <- instrumentsGrpc
          .currencies(instrumentsRequest, metadata)
          .map(_.instruments)
        tcsShares <- instrumentsGrpc
          .shares(instrumentsRequest, metadata)
          .map(_.instruments)
        tcsEtfs <- instrumentsGrpc
          .etfs(instrumentsRequest, metadata)
          .map(_.instruments)
        tcsFutures <- instrumentsGrpc
          .futures(instrumentsRequest, metadata)
          .map(_.instruments)
        infoByFigi = (tcsCurrencies.map(x => x.figi -> (x.ticker, x.name)) ++ tcsShares.map(
          x => x.figi -> (x.ticker, x.name)
        ) ++ tcsEtfs
          .map(x => x.figi -> (x.ticker, x.name)) ++ tcsFutures.map(
          x => x.figi -> (x.ticker, x.name)
        )).toMap
        tcsPositions <- operationsGrpc
          .getPortfolio(PortfolioRequest(brokerAccountId.show), metadata)
          .map(_.positions)
      } yield {
        val positions = tcsPositions.map { x =>
          val (ticker, name) = infoByFigi.getOrElse(x.figi, ("", ""))
          Position(x.figi, ticker, name, BigDecimal(x.quantity.map(_.units).getOrElse(0L)))
        }

        Portfolio(positions.toList)
      }
    }
  }
}
