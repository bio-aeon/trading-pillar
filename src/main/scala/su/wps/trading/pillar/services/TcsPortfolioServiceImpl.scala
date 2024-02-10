package su.wps.trading.pillar.services

import cats.effect.Clock
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Functor, Monad}
import ru.tinkoff.piapi.contract.v1.common.MoneyValue
import ru.tinkoff.piapi.contract.v1.operations.{Operation => TcsOperation}
import su.wps.trading.pillar.data.ProcessContext
import su.wps.trading.pillar.facades.TcsFacade
import su.wps.trading.pillar.models.domain._
import su.wps.trading.pillar.storages.TcsPortfolioOperationStorage
import tofu.WithContext
import tofu.kernel.types.Throws
import tofu.logging.{Logging, Logs}
import tofu.syntax.context._
import tofu.syntax.logging._
import tofu.syntax.raise._

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.FiniteDuration

final class TcsPortfolioServiceImpl[F[_]: Logging: WithContext[*[_], ProcessContext]] private (
  initialYearsAgo: Int,
  operationStorage: TcsPortfolioOperationStorage[F],
  tcsFacade: TcsFacade[F]
)(implicit F: Monad[F], R: Throws[F], clock: Clock[F])
    extends TcsPortfolioService[F] {
  def syncOperations(
    interval: FiniteDuration,
    overlap: FiniteDuration,
    lastDt: Option[ZonedDateTime] = None
  ): F[Option[ZonedDateTime]] =
    for {
      accountId <- context[F].map(_.accountId)
      now <- nowF
      since_? <- (lastDt match {
        case some @ Some(_) => some.pure[F]
        case None => operationStorage.lastOperation(accountId) >>= (_.map(_.createdAt).pure[F])
      })
      until_? = since_?
        .map(_.plusSeconds(interval.toSeconds))
        .map(x => if (x.isBefore(now)) x else now)
      sinceWithoutOverlap = since_?.getOrElse(now.minusYears(initialYearsAgo))
      actualSince = sinceWithoutOverlap.minusSeconds(overlap.toSeconds)
      actualUntil = calcActualUntil(now, sinceWithoutOverlap, until_?).plusSeconds(
        overlap.toSeconds
      )
      _ <- info"Attempt to get new operations. Interval [$actualSince, $actualUntil]"
      tcsOperations <- tcsFacade.operations(actualSince, actualUntil)
      existingOperations <- F.ifM((since_?.isDefined && until_?.isDefined).pure[F])(
        operationStorage
          .operationsByDtRange(accountId, actualSince, actualUntil)
          .map(_.map(x => (x.operationType.show, x.extId.show))),
        Set.empty[(String, String)].pure[F]
      )
      operationsToSave <- tcsOperations
        .filter(x => !existingOperations.contains((x.operationType.name, x.id)))
        .traverse(toDomainOperation(_, accountId))
        .map(_.sortBy(_.createdAt))
      _ <- info"Received ${operationsToSave.length} new tcs operations"
      _ <- operationStorage.saveOperations(operationsToSave)
    } yield operationsToSave.lastOption.map(_.createdAt).orElse(until_?)

  private[services] def toDomainOperation(
    tcsOperation: TcsOperation,
    accountId: AccountId
  ): F[TcsPortfolioOperation] = {
    val instrumentType_? = Option(tcsOperation.instrumentType).filter(_.nonEmpty)
    val figi_? = Option(tcsOperation.figi).filter(_.nonEmpty)

    (
      tcsOperation.price.orRaise[F](new Exception("Empty price")),
      tcsOperation.payment.orRaise[F](new Exception("Empty amount")),
      tcsOperation.date.orRaise[F](new Exception("Empty date"))
    ).flatMapN {
      case (price, amount, date) =>
        (moneyToBigDecimal(price), moneyToBigDecimal(amount)).mapN(
          TcsPortfolioOperation(
            PortfolioOperationId(0),
            PortfolioOperationExtId(tcsOperation.id),
            PortfolioOperationType(tcsOperation.operationType.name),
            instrumentType_?.map(PortfolioInstrumentType(_)),
            PortfolioOperationStatus(tcsOperation.state.name),
            figi_?.map(Figi(_)),
            Currency(tcsOperation.currency),
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

  private[services] def calcActualUntil(
    now: ZonedDateTime,
    since: ZonedDateTime,
    `until_?`: Option[ZonedDateTime]
  ): ZonedDateTime =
    until_?
      .getOrElse {
        val monthAfterSince = since.plusMonths(1)
        Either.cond(now.isAfter(monthAfterSince), monthAfterSince, now).merge
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

  def create[I[_]: Functor, F[_]: Monad: Clock: WithContext[*[_], ProcessContext]: Throws](
    initialYearsAgo: Int,
    operationStorage: TcsPortfolioOperationStorage[F],
    tcsFacade: TcsFacade[F]
  )(implicit logs: Logs[I, F]): I[TcsPortfolioService[F]] =
    logs
      .forService[TcsPortfolioService[F]]
      .map(
        implicit log => new TcsPortfolioServiceImpl[F](initialYearsAgo, operationStorage, tcsFacade)
      )
}
