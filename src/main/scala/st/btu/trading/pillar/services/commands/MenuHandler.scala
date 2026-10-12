package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

// Sends an inline keyboard for the main read-only commands; each button's callbackData is the
// command text itself (e.g. "/positions"), which TgUpdatesProcessImpl routes back as if the user
// had typed it. No Monad/other effect constraint needed: handle just forwards to the gateway.
final class MenuHandler[F[_]](tgGateway: TgGateway[F]) extends CommandHandler[F] {
  import MenuHandler.*

  def command: Command = Command.Menu

  def usage: String = "/menu - inline keyboard for the main read-only commands"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    tgGateway.sendMessageWithKeyboard(chatId, Text, Rows)
}

object MenuHandler {

  private val Text = "Choose a command:"

  // The main read-only commands, built from Command's own values so this list can never spell one
  // differently than the Command it routes back to; order matches docs/telegram-commands.md.
  private[pillar] val Commands: List[String] =
    List(
      Command.Portfolio,
      Command.Positions,
      Command.Performance,
      Command.Alerts,
      Command.Watchlist,
      Command.Dividends,
      Command.Paper
    ).map(c => s"/${c.value}")

  // Two buttons per row, callbackData = the command text verbatim.
  private val Rows: List[List[tg.InlineButton]] =
    Commands.map(c => tg.InlineButton(c, c)).grouped(2).toList
}
