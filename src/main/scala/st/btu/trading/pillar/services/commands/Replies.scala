package st.btu.trading.pillar.services.commands

import cats.Functor
import cats.effect.Clock
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.tg

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, ZonedDateTime}
import scala.math.BigDecimal.RoundingMode

// Formatting/reply building blocks every CommandHandler used to copy by hand: sending a reply
// through TgGateway with CommandReply's sanitization, the "now"/"today" UTC convention most
// handlers use for their own snapshot instant (delegating to data.UtcClock, also used by the
// background processes), a BigDecimal scaled to 2 decimals for display, that same figure read as
// a 0-1 fraction and rendered as a percentage, and the yyyy-MM-dd HH:mm:ss timestamp format a few
// handlers show a date in.
private[pillar] object Replies {

  val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def reply[F[_]](tgGateway: TgGateway[F], chatId: tg.ChatId, text: String): F[Unit] =
    tgGateway.sendMessage(chatId, CommandReply.prepare(text))

  def todayUtc[F[_]: Functor: Clock]: F[LocalDate] = UtcClock.todayUtc[F]

  def nowUtc[F[_]: Functor: Clock]: F[ZonedDateTime] = UtcClock.nowUtc[F]

  def scaled(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toString

  // v is a 0-1 fraction, e.g. pct(0.05) == "5.00%". A figure already expressed in percentage
  // points (Rebalancer's Drift, StockAnalysis's *Pct fields) formats with `scaled` directly
  // instead of this.
  def pct(v: BigDecimal): String = s"${scaled(v * 100)}%"
}
