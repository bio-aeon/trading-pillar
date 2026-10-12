package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import st.btu.trading.pillar.alerts.AlertDispatcher
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

final class UnmuteHandler[F[_]: Monad](tgGateway: TgGateway[F], dispatcher: AlertDispatcher[F])
    extends CommandHandler[F] {
  def command: Command = Command.Unmute

  def usage: String = "/unmute - resume alert delivery"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    dispatcher.unmute.flatMap(_ => Replies.reply(tgGateway, chatId, "Alerts unmuted."))
}
