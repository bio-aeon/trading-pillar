package st.btu.trading.pillar.services

import scala.concurrent.duration.FiniteDuration

final case class PriceHistorySyncConfig(
  interval: FiniteDuration,
  backfillYears: Int,
  overlapDays: Int,
  rateLimitBackoff: FiniteDuration,
  metadataRefresh: FiniteDuration
)
