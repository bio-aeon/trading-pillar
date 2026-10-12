package st.btu.trading.pillar.alerts

import st.btu.trading.pillar.models.market.Ticker

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZonedDateTime

/** A notification raised by this library's alert pipeline, before delivery guards are applied. */
final case class Alert(
  ruleKey: String,
  symbol: Option[Ticker],
  kind: AlertKind,
  title: String,
  body: String,
  triggerValue: Option[BigDecimal],
  at: ZonedDateTime
) {

  /** Stable content hash (hex SHA-256, 64 characters), a defensive dedup key alongside the
    * (ruleKey, symbol, kind) cooldown key - it also catches two different rules emitting the same
    * notification, which the cooldown key alone cannot. Deliberately excludes `ruleKey`.
    */
  def dedupHash: String = {
    val payload = List(
      symbol.map(_.value).getOrElse(""),
      kind.value,
      title,
      body,
      triggerValue.map(_.toString).getOrElse("")
    ).mkString("|")
    val bytes =
      MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))
    bytes.map(b => f"$b%02x").mkString
  }
}
