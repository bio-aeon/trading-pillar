package su.wps.trading.pillar.processes

import cats.Monad
import cats.effect.{Clock, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import sttp.client3.SttpBackend
import su.wps.trading.pillar.gateways.{TcsGateway, TgGateway}
import su.wps.trading.pillar.models.{tcs, tg}
import su.wps.trading.pillar.services.CommandHandleService
import tofu.kernel.types.Throws
import tofu.logging.Logs

object MakeTgUpdatesProcess {
  def apply[I[_]: Monad, F[_]: Temporal: Throws: Clock](
    privateChatId: tg.ChatId,
    tgToken: tg.Token,
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    backend: SttpBackend[F, Any]
  )(implicit logs: Logs[I, F]): I[TgUpdatesProcess[F]] =
    for {
      tgGateway <- TgGateway.create[I, F](tgToken, backend)
      tcsGateway <- TcsGateway
        .create[I, F](brokerAccountId, tcsToken, backend)
      commandHandleService <- CommandHandleService
        .create[I, F](privateChatId, tgGateway, tcsGateway)
      tgUpdatesProcess <- TgUpdatesProcess.create[I, F](tgGateway, commandHandleService)
    } yield tgUpdatesProcess
}
