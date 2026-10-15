package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.AccountId

import java.time.ZonedDateTime

/** One ticker an account tracks for price history sync and alerts. Which source the ticker's data
  * comes from is a setting of the ticker itself, not of the watchlist entry.
  */
final case class WatchlistItem(
  accountId: AccountId,
  ticker: Ticker,
  enabled: Boolean,
  note: Option[String],
  createdAt: ZonedDateTime
)
