package st.btu.trading.pillar

package object analytics {

  // Calendar days in a year: annualizing a total return over an arbitrary span (Performance
  // .annualized) and measuring elapsed time for XIRR (Performance.moneyWeighted) both use this,
  // not the trading-days count below.
  val DaysPerYear = 365

  // Trading days in a year, the default periodsPerYear every Risk statistic annualizes against.
  val TradingDaysPerYear = 252

  // Decimal places a ratio/rate computed through a Double intermediate (log returns, annualized
  // return, Risk's own stats, XIRR) is rounded to, so the result doesn't carry fake precision from
  // the double's binary expansion.
  val ReportScale = 8

  // How far back PortfolioValuationServiceImpl and tax.TaxServiceImpl both load an account's
  // executed operations from, regardless of the window/year actually requested - long enough that
  // a position opened before it is on hand for any report inside it.
  val OperationHistoryYears = 20
}
