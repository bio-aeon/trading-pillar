package su.wps.trading.pillar.processes

import cats.effect.{Async, Resource, Sync}
import sttp.client3.SttpBackend
import su.wps.trading.pillar.facades.TcsFacade
import su.wps.trading.pillar.gateways.TgGateway
import su.wps.trading.pillar.models.{tcs, tg}
import su.wps.trading.pillar.services.CommandHandleService
import tofu.kernel.types.Throws
import tofu.lift.Lift
import tofu.logging.Logs

object MakeTgUpdatesProcessResource {
  def apply[I[_]: Sync, F[_]: Async: Throws: Lift[*[_], I]](
    privateChatId: tg.ChatId,
    tgToken: tg.Token,
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    backend: SttpBackend[F, Any]
  )(implicit logs: Logs[I, F]): Resource[I, TgUpdatesProcess[F]] =
    for {
      tgGateway <- Resource.eval(TgGateway.create[I, F](tgToken, backend))
      tcsFacade <- TcsFacade.resource[I, F](brokerAccountId, tcsToken)
      commandHandleService <- Resource.eval(
        CommandHandleService
          .create[I, F](privateChatId, tgGateway, tcsFacade)
      )
      tgUpdatesProcess <- Resource.eval(
        TgUpdatesProcess.create[I, F](tgGateway, commandHandleService)
      )
    } yield tgUpdatesProcess
}
