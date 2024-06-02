package su.wps.trading.pillar.processes

import cats.Functor
import cats.effect.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
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
import tofu.syntax.context.*
import tofu.syntax.logging.*

import java.time.ZonedDateTime
import scala.concurrent.duration.*

final class TcsPortfolioSyncProcessImpl[F[_]: Temporal: Logging: GenUUID](
  accountStorage: AccountStorage[F],
  tcsPortfolioService: TcsPortfolioService[F]
)(implicit WL: WithLocal[F, ProcessContext])
    extends TcsPortfolioSyncProcess[F] {

  def run: Stream[F, Unit] =
    Stream
      .evals(accountStorage.allAccounts)
      .map(_.id)
      .map { accountId =>
        Stream
          .eval(Ref[F].of(Option.empty[ZonedDateTime]))
          .flatMap(processAccountPortfolioSync(accountId, _))
      }
      .parJoinUnbounded

  private def processAccountPortfolioSync(
    accountId: AccountId,
    lastDtRef: Ref[F, Option[ZonedDateTime]]
  ): Stream[F, Unit] = {
    val syncF = lastDtRef.get.flatMap(
      tcsPortfolioService.syncOperations(1.days, 1.hours, _).flatMap(lastDtRef.set)
    )

    Stream
      .eval(genTraceId >>= (uuid => syncF.local(_.copy(traceId = uuid, accountId = accountId))))
      .flatMap(_ => Stream.sleep(2.minutes))
      .repeat
      .handleErrorWith { e =>
        Stream.eval(errorCause"Tcs portfolio sync process loop failure" (e)) >>
          Stream.sleep(2.minutes) >> processAccountPortfolioSync(accountId, lastDtRef)
      }
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
