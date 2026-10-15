package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.Currency

import java.time.LocalDate

/** A point-in-time view of an account's holdings as of `date`, in `currency`.
  *
  * @param positions
  *   excludes any instrument whose net quantity as of `date` is exactly zero; order is not
  *   otherwise significant
  * @param cash
  *   the base-currency total across every operation currency; a currency with no FX rate on or
  *   before `date` is silently excluded
  * @param total
  *   `cash` plus the sum of every priced position's value
  * @param unpriced
  *   count of positions, mapped or not, whose value is `None`
  */
final case class PositionSnapshot(
  date: LocalDate,
  currency: Currency,
  positions: Vector[PositionValue],
  cash: BigDecimal,
  total: BigDecimal,
  unpriced: Int
)
