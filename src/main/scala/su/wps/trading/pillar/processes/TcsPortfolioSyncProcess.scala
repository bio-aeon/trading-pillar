package su.wps.trading.pillar.processes

import cats.Functor
import cats.effect.Temporal
import cats.syntax.functor._
import fs2.Stream
import su.wps.trading.pillar.models.domain.AccountId
import su.wps.trading.pillar.services.TcsPortfolioService
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._

import java.time.ZonedDateTime
import scala.concurrent.duration._

trait TcsPortfolioSyncProcess[F[_]] {
  def run: Stream[F, Unit]
}

object TcsPortfolioSyncProcess {
  val AccId: AccountId = AccountId(1)

  def create[I[_]: Functor, F[_]: Temporal](
    tcsPortfolioService: TcsPortfolioService[F]
  )(implicit logs: Logs[I, F]): I[TcsPortfolioSyncProcess[F]] =
    logs
      .forService[TcsPortfolioSyncProcess[F]]
      .map(implicit log => new Impl[F](tcsPortfolioService))

  final private class Impl[F[_]: Temporal: Logging](tcsPortfolioService: TcsPortfolioService[F])
      extends TcsPortfolioSyncProcess[F] {
    def run: Stream[F, Unit] =
      processPortfolioSyncLoop()

    private def processPortfolioSyncLoop(lastDt: Option[ZonedDateTime] = None): Stream[F, Unit] =
      Stream
        .eval(tcsPortfolioService.syncOperations(AccId, 1.days, 1.hours, lastDt))
        .flatMap { lastDtNew =>
          Stream.sleep(2.minutes) >> processPortfolioSyncLoop(lastDtNew)
        }
        .handleErrorWith { e =>
          Stream.eval(errorCause"Tcs portfolio sync process loop failure" (e)) >>
            Stream.sleep(2.minutes) >> processPortfolioSyncLoop(lastDt)
        }
  }
}
