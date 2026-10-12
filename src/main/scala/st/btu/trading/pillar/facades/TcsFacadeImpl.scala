package st.btu.trading.pillar.facades

import cats.Monad
import cats.effect.{Async, Resource, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.show.*
import com.google.protobuf.timestamp.Timestamp
import io.grpc.Metadata
import ru.tinkoff.piapi.contract.v1.common.InstrumentStatus
import ru.tinkoff.piapi.contract.v1.instruments.{InstrumentsRequest, InstrumentsServiceFs2Grpc}
import ru.tinkoff.piapi.contract.v1.operations.{
  Operation,
  OperationsRequest,
  OperationsServiceFs2Grpc,
  PortfolioRequest
}
import st.btu.trading.pillar.models.domain.Portfolio
import st.btu.trading.pillar.models.domain.Portfolio.Position
import st.btu.trading.pillar.models.tcs
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}

import java.time.ZonedDateTime

final class TcsFacadeImpl[F[_]: Monad: Logging] private (
  brokerAccountId: tcs.BrokerAccountId,
  token: tcs.Token,
  instrumentsGrpc: InstrumentsServiceFs2Grpc[F, Metadata],
  operationsGrpc: OperationsServiceFs2Grpc[F, Metadata]
) extends TcsFacade[F] {

  private val metadata = TcsGrpc.bearerMetadata(token)

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
      infoByFigi = (tcsCurrencies.map(x => x.figi -> (x.ticker, x.name)) ++ tcsShares.map(x =>
        x.figi -> (x.ticker, x.name)
      ) ++ tcsEtfs
        .map(x => x.figi -> (x.ticker, x.name)) ++ tcsFutures.map(x =>
        x.figi -> (x.ticker, x.name)
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

  def operations(since: ZonedDateTime, until: ZonedDateTime): F[List[Operation]] =
    operationsGrpc
      .getOperations(
        OperationsRequest(
          brokerAccountId.show,
          zonedDtToTimestamp(since).some,
          zonedDtToTimestamp(until).some
        ),
        metadata
      )
      .map(_.operations.toList)

  private def zonedDtToTimestamp(dt: ZonedDateTime): Timestamp = {
    val i = dt.toInstant
    Timestamp.of(i.getEpochSecond, i.getNano)
  }
}

object TcsFacadeImpl {

  /** Wires a `TcsFacade` authenticated as `brokerAccountId`, holding the gRPC channel open for the
    * resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async](
    brokerAccountId: tcs.BrokerAccountId,
    token: tcs.Token
  )(implicit logs: Logs[I, F], liftFI: Lift[F, I]): Resource[I, TcsFacadeImpl[F]] =
    for {
      channel <- TcsGrpc.channel[I]
      instrumentsGrpc <- InstrumentsServiceFs2Grpc.stubResource[F](channel).mapK(liftFI.liftF)
      operationsGrpc <- OperationsServiceFs2Grpc.stubResource[F](channel).mapK(liftFI.liftF)
      case implicit0(log: Logging[F]) <- Resource.eval(logs.forService[TcsFacade[F]])
    } yield new TcsFacadeImpl[F](brokerAccountId, token, instrumentsGrpc, operationsGrpc)
}
