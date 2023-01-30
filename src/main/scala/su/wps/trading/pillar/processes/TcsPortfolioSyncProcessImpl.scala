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
import su.wps.trading.pillar.storages.TcsPortfolioOperationStorage
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
]](tcsPortfolioService: TcsPortfolioService[F])
    extends TcsPortfolioSyncProcess[F] {

  val AccId: AccountId = AccountId(1)

  def run: Stream[F, Unit] = processPortfolioSyncLoop()

  private def processPortfolioSyncLoop(lastDt: Option[ZonedDateTime] = None): Stream[F, Unit] =
    Stream
      .eval {
        val syncF = tcsPortfolioService.syncOperations(1.days, 1.hours, lastDt)
        genTraceId >>= (uuid => syncF.local(_.copy(traceId = uuid, accountId = AccId)))
      }
      .flatMap { lastDtNew =>
        Stream.sleep(2.minutes) >> processPortfolioSyncLoop(lastDtNew)
      }
      .handleErrorWith { e =>
        Stream.eval(errorCause"Tcs portfolio sync process loop failure" (e)) >>
          Stream.sleep(2.minutes) >> processPortfolioSyncLoop(lastDt)
      }

  private def genTraceId: F[String] =
    GenUUID[F].randomUUID.map(_.toString)
}

object TcsPortfolioSyncProcessImpl {

  def resource[I[_]: Sync, F[_]: Async: GenUUID: Lift[*[_], I]: WithLocal[*[_], ProcessContext]](
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F]
  )(implicit logs: Logs[I, F]): Resource[I, TcsPortfolioSyncProcessImpl[F]] =
    for {
      tcsFacade <- TcsFacadeImpl.resource[I, F](brokerAccountId, tcsToken)
      tcsPortfolioService <- Resource.eval(
        TcsPortfolioServiceImpl.create[I, F](tcsPortfolioOperationStorage, tcsFacade)
      )
      tcsPortfolioSyncProcess <- Resource.eval(
        TcsPortfolioSyncProcessImpl.create[I, F](tcsPortfolioService)
      )
    } yield tcsPortfolioSyncProcess

  def create[I[_]: Functor, F[_]: Temporal: GenUUID: WithLocal[*[_], ProcessContext]](
    tcsPortfolioService: TcsPortfolioService[F]
  )(implicit logs: Logs[I, F]): I[TcsPortfolioSyncProcessImpl[F]] =
    logs
      .forService[TcsPortfolioSyncProcess[F]]
      .map(implicit log => new TcsPortfolioSyncProcessImpl[F](tcsPortfolioService))
}
