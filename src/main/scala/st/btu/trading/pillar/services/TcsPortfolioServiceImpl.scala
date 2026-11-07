package st.btu.trading.pillar.services

import cats.effect.Clock
import cats.syntax.applicative.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import cats.{Functor, Monad}
import ru.tinkoff.piapi.contract.v1.common.MoneyValue
import ru.tinkoff.piapi.contract.v1.operations.OperationItem
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.facades.TcsFacade
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.storages.TcsPortfolioOperationStorage
import tofu.WithContext
import tofu.kernel.types.Throws
import tofu.logging.{Logging, Logs}
import tofu.syntax.context.*
import tofu.syntax.logging.*
import tofu.syntax.raise.*

import java.time.{Instant, ZoneId, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.FiniteDuration

final class TcsPortfolioServiceImpl[F[_]: Logging] private (
  config: TcsPortfolioSyncConfig,
  operationStorage: TcsPortfolioOperationStorage[F],
  tcsFacade: TcsFacade[F]
)(implicit F: Monad[F], R: Throws[F], clock: Clock[F], WC: WithContext[F, ProcessContext])
    extends TcsPortfolioService[F] {
  import TcsPortfolioServiceImpl.*

  def syncOperations(
    interval: FiniteDuration,
    overlap: FiniteDuration,
    lastDt: Option[ZonedDateTime] = None
  ): F[ZonedDateTime] =
    for {
      accountId <- context[F].map(_.accountId)
      now <- nowF
      since_? <- lastDt match {
        case some @ Some(_) => some.pure[F]
        case None => operationStorage.lastOperation(accountId) >>= (_.map(_.createdAt).pure[F])
      }
      sinceWithoutOverlap = since_?.getOrElse(now.minusYears(config.initialYearsAgo))
      nextDt = sinceWithoutOverlap.plusSeconds(interval.toSeconds)
      untilWithoutOverlap = Either.cond(nextDt.isBefore(now), nextDt, now).merge
      actualSince = earlier(
        sinceWithoutOverlap.minusSeconds(overlap.toSeconds),
        now.minusSeconds(config.rereadWindow.toSeconds)
      )
      actualUntil = untilWithoutOverlap.plusSeconds(overlap.toSeconds)
      _ <- info"Attempt to get operations. Interval [$actualSince, $actualUntil]"
      tcsOperations <- tcsFacade.operations(actualSince, actualUntil)
      records <- tcsOperations
        .traverse(toDomainOperation(_, accountId))
        .map(_.sortBy(_.createdAt).map(TcsOperationTranslation.recordOf))
      _ <- info"Received ${records.length} tcs operations"
      _ <- operationStorage.record(records)
    } yield untilWithoutOverlap

  def rebuildTranslations: F[Unit] =
    for {
      accountId <- context[F].map(_.accountId)
      now <- nowF
      stored <- operationStorage.operationsByDtRange(accountId, EarliestOperation, now)
      _ <- info"Recording again the ${stored.size} stored tcs operations with their translations"
      _ <- stored.toList
        .sortBy(_.createdAt)
        .grouped(RebuildBatchSize)
        .toList
        .traverse_(batch => operationStorage.record(batch.map(TcsOperationTranslation.recordOf)))
    } yield ()

  private[services] def toDomainOperation(
    tcsOperation: OperationItem,
    accountId: AccountId
  ): F[TcsPortfolioOperation] = {
    val instrumentType_? = Option(tcsOperation.instrumentType).filter(_.nonEmpty)
    val figi_? = Option(tcsOperation.figi).filter(_.nonEmpty)

    (
      tcsOperation.price.orRaise[F](new Exception("Empty price")),
      tcsOperation.payment.orRaise[F](new Exception("Empty amount")),
      tcsOperation.date.orRaise[F](new Exception("Empty date"))
    ).flatMapN { case (price, payment, date) =>
      (moneyToBigDecimal(price), moneyToBigDecimal(payment)).mapN(
        TcsPortfolioOperation(
          PortfolioOperationId(0),
          PortfolioOperationExtId(tcsOperation.id),
          PortfolioOperationType(tcsOperation.`type`.name),
          instrumentType_?.map(PortfolioInstrumentType(_)),
          PortfolioOperationStatus(tcsOperation.state.name),
          figi_?.map(Figi(_)),
          Currency(payment.currency),
          _,
          tcsOperation.quantity.toInt,
          tcsOperation.quantityRest.toInt,
          _,
          ProtocolVersion(TcsFacade.ApiVersion),
          ZonedDateTime
            .ofInstant(Instant.ofEpochSecond(date.seconds, date.nanos), ZoneId.of("UTC")),
          accountId
        )
      )
    }
  }

  private[services] def nowF: F[ZonedDateTime] =
    clock.realTime
      .map(_.toMillis)
      .map(Instant.ofEpochMilli)
      .map(ZonedDateTime.ofInstant(_, ZoneId.systemDefault()))

  private def moneyToBigDecimal(money: MoneyValue): F[BigDecimal] =
    (money.units > 0 && money.nano < 0)
      .pure[F]
      .ifM(
        R.raise(new Exception(s"Inconsistent money value $money")),
        BigDecimal(money.units + money.nano / 1000000000.0).pure[F]
      )

}

object TcsPortfolioServiceImpl {

  // No operation is dated before the epoch, so reading from it reads every stored operation.
  private val EarliestOperation: ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)

  // How many stored operations a rebuild records per storage call.
  private val RebuildBatchSize = 500

  private def earlier(a: ZonedDateTime, b: ZonedDateTime): ZonedDateTime =
    if (b.isBefore(a)) b else a

  /** Wires a `TcsPortfolioService` backed by `tcsFacade` and `operationStorage`. */
  def create[I[_]: Functor, F[_]: Monad: Clock: Throws](
    config: TcsPortfolioSyncConfig,
    operationStorage: TcsPortfolioOperationStorage[F],
    tcsFacade: TcsFacade[F]
  )(implicit logs: Logs[I, F], WC: WithContext[F, ProcessContext]): I[TcsPortfolioService[F]] =
    logs
      .forService[TcsPortfolioService[F]]
      .map(implicit log => new TcsPortfolioServiceImpl[F](config, operationStorage, tcsFacade))
}
