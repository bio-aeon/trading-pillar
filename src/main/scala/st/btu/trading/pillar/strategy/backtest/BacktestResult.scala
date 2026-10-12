package st.btu.trading.pillar.strategy.backtest

import io.circe.generic.JsonCodec
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.strategy.{Side, StrategyId}

import java.time.LocalDate

/** One filled order from a backtest or paper simulation. */
@JsonCodec
final case class Trade(
  date: LocalDate,
  side: Side,
  quantity: Long,
  price: BigDecimal,
  commission: BigDecimal,
  reason: String
)

/** One day's mark-to-market equity, cash and position size in a backtest's equity curve. */
@JsonCodec
final case class EquityPoint(date: LocalDate, equity: BigDecimal, cash: BigDecimal, quantity: Long)

/** A signal that was never filled, with `reason` explaining why - including a signal produced on
  * the series' last bar, which has no next bar to fill against.
  */
@JsonCodec
final case class SkippedSignal(date: LocalDate, reason: String)

/** The outcome of running one strategy over one ticker's bars between `dateFrom` and `dateTo`. */
final case class BacktestResult(
  strategyId: StrategyId,
  ticker: Ticker,
  parameters: Map[String, String],
  dateFrom: LocalDate,
  dateTo: LocalDate,
  config: BacktestConfig,
  trades: List[Trade],
  equityCurve: List[EquityPoint],
  metrics: BacktestMetrics,
  skipped: List[SkippedSignal]
)

/** Multi-ticker counterpart of [[BacktestResult]]: one shared cash balance and one combined equity
  * curve and metrics across every ticker, but a separate, per-ticker trade log. `dateFrom`/`dateTo`
  * are the merged date range across every ticker's own bars. `BacktestService.runPortfolio`
  * projects this into a `BacktestResult` for persistence (ticker set to every ticker joined by
  * `","`), so storage needs no separate schema.
  */
final case class PortfolioBacktestResult(
  strategyId: StrategyId,
  tickers: List[Ticker],
  parameters: Map[String, String],
  dateFrom: LocalDate,
  dateTo: LocalDate,
  config: BacktestConfig,
  tradesByTicker: Map[Ticker, List[Trade]],
  equityCurve: List[EquityPoint],
  metrics: BacktestMetrics,
  skipped: List[SkippedSignal]
)
