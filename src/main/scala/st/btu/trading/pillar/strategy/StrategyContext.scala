package st.btu.trading.pillar.strategy

import st.btu.trading.pillar.models.market.Ticker

import java.time.LocalDate

/** Everything a [[Strategy]] sees when evaluating one bar: the bar history up to and including it,
  * this strategy's own open position if any, and the portfolio-level cash/equity available for
  * sizing.
  */
final case class StrategyContext(
  ticker: Ticker,
  history: BarHistory,
  position: Option[OpenPosition],
  portfolio: PortfolioView
)

/** A strategy's own open position in `ticker`, independent of any real brokerage position. */
final case class OpenPosition(
  ticker: Ticker,
  quantity: Long,
  averageCost: BigDecimal,
  openedOn: LocalDate
)

/** Cash and total equity available to size a signal's `Sizing` against. */
final case class PortfolioView(cash: BigDecimal, equity: BigDecimal)
