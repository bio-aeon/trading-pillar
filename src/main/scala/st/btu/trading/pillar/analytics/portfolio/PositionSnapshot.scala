package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.Currency

import java.time.LocalDate

/** A point-in-time view of an account's holdings as of `date`, in `currency`.
  *
  * @param positions
  *   one per instrument whose net quantity as of `date` is not zero, except a futures contract that
  *   has expired by `date`; `positionsAsOf` orders them by instrument: the broker's value, then the
  *   instrument id
  * @param cash
  *   the base-currency total across every operation currency; a currency with no FX rate on or
  *   before `date` is silently excluded
  * @param total
  *   `cash` plus the sum of every priced position's value
  * @param dayChange
  *   what the latest price moves added to `total`: the sum, over every position with a `value` and
  *   a `dayReturn`, of `quantity * (last close - previous close)`, converted to `currency` at the
  *   rate the position's `value` used, so a currency move does not count as a price move. A futures
  *   contract counts the same way with the money one point is worth in the factor, at the rate its
  *   exposure used, and adds nothing without terms or without a rate. The variation margin is not
  *   in it: the margin moves `total` at the clearings while the close moves all day, so while
  *   futures are held `total - dayChange` is not the day before's total. A contract sold during the
  *   day is not held at `date`, so its move is in the cash its margin brought and not here, as for
  *   a share sold during the day; `0` when no position has a move
  * @param unpriced
  *   count of positions, mapped or not, whose value is `None`; a futures contract is never one, its
  *   value being zero
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
