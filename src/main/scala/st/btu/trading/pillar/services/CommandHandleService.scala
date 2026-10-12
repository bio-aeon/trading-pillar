package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.services.commands.ParsedCommand

/** Routes a parsed command to the handler registered for it. */
trait CommandHandleService[F[_]] {

  /** Handles `parsed` for `chatId`, replying through the configured gateway. */
  def handleCommand(chatId: tg.ChatId, parsed: ParsedCommand): F[Unit]
}
