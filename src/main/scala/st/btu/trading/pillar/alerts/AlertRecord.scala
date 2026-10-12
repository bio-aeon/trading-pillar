package st.btu.trading.pillar.alerts

import st.btu.trading.pillar.models.market.Ticker

import java.time.ZonedDateTime

/** One delivery attempt, successful or suppressed.
  *
  * @param suppressedBy
  *   the guard's reason when suppressed, `None` when delivered
  * @param channels
  *   the channel names actually sent to; empty when suppressed
  */
final case class AlertRecord(
  ruleKey: String,
  ticker: Option[Ticker],
  kind: AlertKind,
  dedupHash: String,
  message: String,
  deliveredAt: ZonedDateTime,
  suppressedBy: Option[String],
  channels: List[String]
)
