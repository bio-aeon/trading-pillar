package st.btu.trading.pillar.storages

import st.btu.trading.pillar.alerts.{AlertKind, AlertRecord}
import st.btu.trading.pillar.models.market.Ticker

import java.time.ZonedDateTime

/** Delivery history backing the alert dispatcher's guards and the `/alerts` command. */
trait AlertHistoryStorage[F[_]] {

  /** The last delivered (non-suppressed) record for this key, for the cooldown guard. */
  def lastDelivered(
    ruleKey: String,
    ticker: Option[Ticker],
    kind: AlertKind
  ): F[Option[AlertRecord]]

  /** Count of delivered (non-suppressed) records for `ruleKey` since `since`, for the daily cap
    * guard.
    */
  def deliveredCountSince(ruleKey: String, since: ZonedDateTime): F[Int]

  /** Whether a delivered record with this content hash exists since `since`, for the dedup guard.
    */
  def existsDedup(hash: String, since: ZonedDateTime): F[Boolean]

  /** Records one delivery attempt, successful or suppressed. */
  def record(r: AlertRecord): F[Unit]

  /** The most recent records, delivered and suppressed alike, newest first. */
  def recent(limit: Int): F[List[AlertRecord]]
}
