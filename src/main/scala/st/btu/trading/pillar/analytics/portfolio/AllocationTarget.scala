package st.btu.trading.pillar.analytics.portfolio

import enumeratum.values.*
import st.btu.trading.pillar.models.domain.AccountId

/** The breakdown an [[AllocationTarget]]'s `key` is measured against: one of the three `Allocation`
  * buckets, or a single ticker looked up directly from a position snapshot.
  */
sealed abstract class Dimension(val value: String) extends StringEnumEntry

object Dimension extends StringEnum[Dimension] {
  case object InstrumentType extends Dimension("instrument_type")
  case object Currency extends Dimension("currency")
  case object Exchange extends Dimension("exchange")
  case object Ticker extends Dimension("ticker")

  val values = findValues
}

/** One operator-defined allocation target: the target weight this account wants for `key` of
  * `dimension`, and how much drift (see `Rebalancer.drift`) is tolerated before it counts as
  * breached.
  *
  * @param key
  *   the bucket of `dimension`, matched against the keys of the matching `Allocation` breakdown; a
  *   `Currency` key is first read as a [[st.btu.trading.pillar.models.domain.Currency]], so `usd`
  *   reads the weight of `USD`; a `Ticker` key is matched against position tickers
  * @param targetPct
  *   percentage, e.g. `60` means 60% (not a 0-1 fraction like `Allocation`'s own weight maps)
  * @param driftThresholdPct
  *   percentage points of drift tolerated before `breached`
  */
final case class AllocationTarget(
  id: AllocationTargetId,
  accountId: AccountId,
  dimension: Dimension,
  key: String,
  targetPct: BigDecimal,
  driftThresholdPct: BigDecimal,
  enabled: Boolean
)
