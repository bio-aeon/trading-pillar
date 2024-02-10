package su.wps.trading.pillar.processes

import cats.Functor
import cats.effect.{Async, Resource, Sync, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.Stream
import su.wps.trading.pillar.data.ProcessContext
import su.wps.trading.pillar.facades.TcsFacadeImpl
import su.wps.trading.pillar.models.domain.AccountId
import su.wps.trading.pillar.models.tcs
import su.wps.trading.pillar.services.{TcsPortfolioService, TcsPortfolioServiceImpl}
import su.wps.trading.pillar.storages.{AccountStorage, TcsPortfolioOperationStorage}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}
import tofu.syntax.context._
import tofu.syntax.logging._

import java.time.ZonedDateTime
import scala.concurrent.duration._

final class TcsPortfolioSyncProcessImpl[F[_]: Temporal: Logging: GenUUID: WithLocal[
  *[_],
  ProcessContext
]](accountStorage: AccountStorage[F], tcsPortfolioService: TcsPortfolioService[F])
    extends TcsPortfolioSyncProcess[F] {

  def run: Stream[F, Unit] =
    Stream
      .evals(accountStorage.allAccounts)
      .map(_.id)
      .map(processAccountPortfolioSync(_))
      .parJoinUnbounded

  private def processAccountPortfolioSync(
    accountId: AccountId,
    lastDt: Option[ZonedDateTime] = None
  ): Stream[F, Unit] =
    Stream
      .eval {
        val syncF = tcsPortfolioService.syncOperations(1.days, 1.hours, lastDt)
        genTraceId >>= (uuid => syncF.local(_.copy(traceId = uuid, accountId = accountId)))
      }
      .flatMap { lastDtNew =>
        Stream.sleep(2.minutes) >> processAccountPortfolioSync(accountId, lastDtNew)
      }
      .handleErrorWith { e =>
        Stream.eval(errorCause"Tcs portfolio sync process loop failure" (e)) >>
          Stream.sleep(2.minutes) >> processAccountPortfolioSync(accountId, lastDt)
      }

  private def genTraceId: F[String] =
    GenUUID[F].randomUUID.map(_.toString)
}

object TcsPortfolioSyncProcessImpl {

  def resource[I[_]: Sync, F[_]: Async: GenUUID: Lift[*[_], I]: WithLocal[*[_], ProcessContext]](
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    initialYearsAgo: Int,
    accountStorage: AccountStorage[F],
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F]
  )(implicit logs: Logs[I, F]): Resource[I, TcsPortfolioSyncProcessImpl[F]] =
    for {
      tcsFacade <- TcsFacadeImpl.resource[I, F](brokerAccountId, tcsToken)
      tcsPortfolioService <- Resource.eval(
        TcsPortfolioServiceImpl
          .create[I, F](initialYearsAgo, tcsPortfolioOperationStorage, tcsFacade)
      )
      tcsPortfolioSyncProcess <- Resource.eval(
        TcsPortfolioSyncProcessImpl.create[I, F](accountStorage, tcsPortfolioService)
      )
    } yield tcsPortfolioSyncProcess

  def create[I[_]: Functor, F[_]: Temporal: GenUUID: WithLocal[*[_], ProcessContext]](
    accountStorage: AccountStorage[F],
    tcsPortfolioService: TcsPortfolioService[F]
  )(implicit logs: Logs[I, F]): I[TcsPortfolioSyncProcessImpl[F]] =
    logs
      .forService[TcsPortfolioSyncProcess[F]]
      .map(implicit log => new TcsPortfolioSyncProcessImpl[F](accountStorage, tcsPortfolioService))
}
