package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

final class HelpHandler[F[_]](tgGateway: TgGateway[F], usages: List[String])
    extends CommandHandler[F] {
  def command: Command = Command.Help

  def usage: String = HelpHandler.Usage

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    Replies.reply(tgGateway, chatId, usages.mkString("\n"))
}

object HelpHandler {
  private[pillar] val Usage = "/help - list available commands"
}
