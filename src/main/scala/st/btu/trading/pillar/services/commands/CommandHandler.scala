package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

/** One Telegram command's implementation. */
trait CommandHandler[F[_]] {

  /** The command this handler responds to. */
  def command: Command

  /** Shown by `/help` and reused as the "Usage: ..." reply for malformed arguments. Like every
    * reply it passes through the Markdown sanitizer, which drops `[`, `]`, `*` and backticks and
    * turns `_` into spaces, so optional parts go in parentheses.
    */
  def usage: String

  /** Handles `args` for `chatId`. */
  def handle(chatId: tg.ChatId, args: List[String]): F[Unit]
}
