package st.btu.trading.pillar.alerts

import cats.Functor
import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.models.tg.MarkdownSanitizer
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

final class TelegramAlertChannel[F[_]: Logging](tgGateway: TgGateway[F], chatId: tg.ChatId)(implicit
  F: Monad[F]
) extends AlertChannel[F] {

  def name: String = TelegramAlertChannel.Name

  def send(alert: Alert): F[Unit] =
    debug"Sending Telegram alert for ${alert.ruleKey} (${alert.kind.value})" >>
      tgGateway.sendMessage(chatId, TelegramAlertChannel.format(alert))
}

object TelegramAlertChannel {

  /** This channel's registered name. */
  val Name: String = "telegram"

  // Short Markdown (Telegram legacy parse_mode=Markdown) message: bold title, optional symbol,
  // then the body on its own line. System alerts get a "SYSTEM: " prefix ahead of the bold title,
  // so they stand out from ordinary per-symbol/per-strategy alerts in the chat.
  private[pillar] def format(alert: Alert): String = {
    val systemPrefix = if (alert.kind == AlertKind.System) "SYSTEM: " else ""
    val symbolSuffix = alert.symbol.fold("")(t => s" (${t.value})")
    val title = MarkdownSanitizer.sanitize(alert.title)
    val body = MarkdownSanitizer.sanitize(alert.body)
    s"$systemPrefix*$title*$symbolSuffix\n$body"
  }

  /** Wires an [[AlertChannel]] that delivers to `chatId` through `tgGateway`. */
  def create[I[_]: Functor, F[_]: Monad](tgGateway: TgGateway[F], chatId: tg.ChatId)(implicit
    logs: Logs[I, F]
  ): I[TelegramAlertChannel[F]] =
    logs
      .forService[AlertChannel[F]]
      .map(implicit log => new TelegramAlertChannel[F](tgGateway, chatId))
}
