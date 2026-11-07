package st.btu.trading.pillar.facades

import cats.Monad
import cats.effect.{Async, Resource, Sync}
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.show.*
import com.google.protobuf.timestamp.Timestamp
import io.grpc.Metadata
import ru.tinkoff.piapi.contract.v1.operations.{
  GetOperationsByCursorRequest,
  OperationItem,
  OperationsServiceFs2Grpc
}
import st.btu.trading.pillar.models.tcs
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}

import java.time.ZonedDateTime

final class TcsFacadeImpl[F[_]: Monad: Logging] private (
  brokerAccountId: tcs.BrokerAccountId,
  token: tcs.Token,
  operationsGrpc: OperationsServiceFs2Grpc[F, Metadata]
) extends TcsFacade[F] {

  private val metadata = TcsGrpc.bearerMetadata(token)

  def operations(since: ZonedDateTime, until: ZonedDateTime): F[List[OperationItem]] =
    TcsFacadeImpl.allOperations(
      operationsGrpc,
      metadata,
      brokerAccountId,
      zonedDtToTimestamp(since),
      zonedDtToTimestamp(until)
    )

  private def zonedDtToTimestamp(dt: ZonedDateTime): Timestamp = {
    val i = dt.toInstant
    Timestamp.of(i.getEpochSecond, i.getNano)
  }
}

object TcsFacadeImpl {

  // The most operations GetOperationsByCursor returns per page.
  private val OperationsPageLimit = 1000

  private[facades] def allOperations[F[_]: Monad](
    operationsGrpc: OperationsServiceFs2Grpc[F, Metadata],
    metadata: Metadata,
    brokerAccountId: tcs.BrokerAccountId,
    since: Timestamp,
    until: Timestamp
  ): F[List[OperationItem]] =
    Monad[F].tailRecM((Option.empty[String], Vector.empty[OperationItem])) {
      case (cursor, fetched) =>
        val request = GetOperationsByCursorRequest(
          accountId = brokerAccountId.show,
          from = since.some,
          to = until.some,
          cursor = cursor,
          limit = OperationsPageLimit.some,
          withoutTrades = true.some
        )
        operationsGrpc.getOperationsByCursor(request, metadata).map { page =>
          val all = fetched ++ page.items
          Either.cond(!page.hasNext, all.toList, (page.nextCursor.some, all))
        }
    }

  /** Wires a `TcsFacade` authenticated as `brokerAccountId`, holding the gRPC channel open for the
    * resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async](
    brokerAccountId: tcs.BrokerAccountId,
    token: tcs.Token
  )(implicit logs: Logs[I, F], liftFI: Lift[F, I]): Resource[I, TcsFacadeImpl[F]] =
    for {
      channel <- TcsGrpc.channel[I]
      operationsGrpc <- OperationsServiceFs2Grpc.stubResource[F](channel).mapK(liftFI.liftF)
      facade <- Resource.eval(
        logs
          .forService[TcsFacade[F]]
          .map(implicit log => new TcsFacadeImpl[F](brokerAccountId, token, operationsGrpc))
      )
    } yield facade
}
