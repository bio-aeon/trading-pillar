package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.Currency

import java.time.LocalDate

/** A point-in-time view of an account's holdings as of `date`, in `currency`.
  *
  * @param positions
  *   one per instrument whose net quantity as of `date` is not zero; `positionsAsOf` orders them by
  *   instrument: the broker's value, then the instrument id
  * @param cash
  *   the base-currency total across every operation currency; a currency with no FX rate on or
  *   before `date` is silently excluded
  * @param total
  *   `cash` plus the sum of every priced position's value
  * @param dayChange
  *   what the latest price moves added to `total`: the sum, over every position with a `value` and
  *   a `dayReturn`, of `quantity * (last close - previous close)`, converted to `currency` at the
  *   rate the position's `value` used, so a currency move does not count as a price move; `0` when
  *   no position has both
  * @param unpriced
  *   count of positions, mapped or not, whose value is `None`
  */
final case class PositionSnapshot(
  date: LocalDate,
  currency: Currency,
  positions: Vector[PositionValue],
  cash: BigDecimal,
  total: BigDecimal,
  dayChange: BigDecimal,
  unpriced: Int
)
