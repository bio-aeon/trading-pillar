package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.AccountId

import java.time.ZonedDateTime

/** One ticker an account tracks for price history sync and alerts. */
final case class WatchlistItem(
  accountId: AccountId,
  ticker: Ticker,
  source: Source,
  enabled: Boolean,
  note: Option[String],
  createdAt: ZonedDateTime
)
