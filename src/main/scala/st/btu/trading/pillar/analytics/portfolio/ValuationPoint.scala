package st.btu.trading.pillar.analytics.portfolio

import java.time.LocalDate

// One day's valuation of an account's portfolio, as stored through PortfolioValuationStorage.
// `invested` is the cumulative net external flow (deposits positive, withdrawals negative) up to
// and including `date`, i.e. every OPERATION_TYPE_INPUT/OUTPUT amount from account inception
// through this date. `cashFlow` is just this date's own external flow (0 on a day with neither).
final case class ValuationPoint(
  date: LocalDate,
  value: BigDecimal,
  invested: BigDecimal,
  cashFlow: BigDecimal
)
