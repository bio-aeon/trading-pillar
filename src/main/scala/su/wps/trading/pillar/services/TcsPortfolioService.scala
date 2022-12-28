package su.wps.trading.pillar.services

import cats.effect.Clock
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.{Functor, Monad}
import ru.tinkoff.piapi.contract.v1.common.MoneyValue
import ru.tinkoff.piapi.contract.v1.operations.{Operation => TcsOperation}
import su.wps.trading.pillar.facades.TcsFacade
import su.wps.trading.pillar.models.domain._
import su.wps.trading.pillar.storages.TcsPortfolioOperationStorage
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.concurrent.duration.FiniteDuration

trait TcsPortfolioService[F[_]] {
  def syncOperations(
    accountId: AccountId,
    interval: FiniteDuration,
    overlap: FiniteDuration,
    lastDt: Option[ZonedDateTime] = None
  ): F[Option[ZonedDateTime]]
}

object TcsPortfolioService {

  def create[I[_]: Functor, F[_]: Monad: Clock](
    operationStorage: TcsPortfolioOperationStorage[F],
    tcsFacade: TcsFacade[F]
  )(implicit logs: Logs[I, F]): I[TcsPortfolioService[F]] =
    logs
      .forService[TcsPortfolioService[F]]
      .map(implicit log => new Impl[F](operationStorage, tcsFacade))

  private final class Impl[F[_]: Logging](
    operationStorage: TcsPortfolioOperationStorage[F],
    tcsFacade: TcsFacade[F]
  )(implicit F: Monad[F], clock: Clock[F])
      extends TcsPortfolioService[F] {
    def syncOperations(
      accountId: AccountId,
      interval: FiniteDuration,
      overlap: FiniteDuration,
      lastDt: Option[ZonedDateTime] = None
    ): F[Option[ZonedDateTime]] =
      for {
        now <- nowF
        since_? <- (lastDt match {
          case some @ Some(_) => some.pure[F]
          case None => operationStorage.lastOperation >>= (_.map(_.createdAt).pure[F])
        })
        until_? = since_?
          .map(_.plusSeconds(interval.toSeconds))
          .map(x => if (x.isBefore(now)) x else now)
        actualSince = since_?.getOrElse(now.minusYears(20)).minusSeconds(overlap.toSeconds)
        actualUntil = until_?.getOrElse(now).plusSeconds(overlap.toSeconds)
        _ <- info"Attempt to get new operations. Interval [$actualSince, $actualUntil]"
        tcsOperations <- tcsFacade.operations(actualSince, actualUntil)
        existingOperations <- F.ifM((since_?.isDefined && until_?.isDefined).pure[F])(
          operationStorage
            .operationsByDtRange(actualSince, actualUntil)
            .map(_.map(x => (x.operationType.show, x.extId.show))),
          Set.empty[(String, String)].pure[F]
        )
        operationsToSave = tcsOperations
          .filter(x => !existingOperations.contains((x.operationType.name, x.id)))
          .map(toDomainOperation(_, accountId))
          .sortBy(_.createdAt)
        _ <- info"Received ${operationsToSave.length} new tcs operations"
        _ <- operationStorage.saveOperations(operationsToSave)
      } yield operationsToSave.lastOption.map(_.createdAt).orElse(until_?)

    private[services] def toDomainOperation(
      tcsOperation: TcsOperation,
      accountId: AccountId
    ): TcsPortfolioOperation = {
      val price = tcsOperation.price.getOrElse(throw new Exception("Empty price"))
      val amount = tcsOperation.payment.getOrElse(throw new Exception("Empty amount"))
      val date = tcsOperation.date.getOrElse(throw new Exception("Empty date"))
      val instrumentType_? = Option(tcsOperation.instrumentType).filter(_.nonEmpty)
      val figi_? = Option(tcsOperation.figi).filter(_.nonEmpty)

      TcsPortfolioOperation(
        PortfolioOperationId(0),
        PortfolioOperationExtId(tcsOperation.id),
        PortfolioOperationType(tcsOperation.operationType.name),
        instrumentType_?.map(PortfolioInstrumentType(_)),
        PortfolioOperationStatus(tcsOperation.state.name),
        figi_?.map(Figi(_)),
        Currency(tcsOperation.currency),
        moneyToBigDecimal(price),
        tcsOperation.quantity.toInt,
        tcsOperation.quantityRest.toInt,
        moneyToBigDecimal(amount),
        ProtocolVersion(TcsFacade.apiVersion),
        ZonedDateTime
          .ofInstant(Instant.ofEpochSecond(date.seconds, date.nanos), ZoneId.of("UTC")),
        accountId
      )
    }

    private[services] def nowF: F[ZonedDateTime] =
      clock.realTime
        .map(_.toMillis)
        .map(Instant.ofEpochMilli)
        .map(ZonedDateTime.ofInstant(_, ZoneId.systemDefault()))

    private def moneyToBigDecimal(money: MoneyValue): BigDecimal = {
      if (money.units > 0 && money.nano < 0) {
        throw new Exception(s"Inconsistent money value $money")
      }

      BigDecimal(money.units + money.nano / 1000000000.0)
    }

  }
}
