package su.wps.trading.pillar.processes

import cats.effect.{Async, Resource, Sync}
import su.wps.trading.pillar.facades.TcsFacade
import su.wps.trading.pillar.models.tcs
import su.wps.trading.pillar.services.TcsPortfolioService
import su.wps.trading.pillar.storages.TcsPortfolioOperationStorage
import tofu.lift.Lift
import tofu.logging.Logs

object MakeTcsPortfolioSyncProcessResource {
  def apply[I[_]: Sync, F[_]: Async: Lift[*[_], I]](
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F]
  )(implicit logs: Logs[I, F]): Resource[I, TcsPortfolioSyncProcess[F]] =
    for {
      tcsFacade <- TcsFacade.resource[I, F](brokerAccountId, tcsToken)
      tcsPortfolioService <- Resource.eval(
        TcsPortfolioService.create[I, F](tcsPortfolioOperationStorage, tcsFacade)
      )
      tcsPortfolioSyncProcess <- Resource.eval(
        TcsPortfolioSyncProcess.create[I, F](tcsPortfolioService)
      )
    } yield tcsPortfolioSyncProcess
}
