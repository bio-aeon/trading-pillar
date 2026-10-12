package st.btu.trading.pillar.strategy.backtest

import io.circe.generic.JsonCodec

/** Summary performance figures for a finished backtest.
  *
  * @param totalReturn
  *   fraction, e.g. `0.05` for +5% (not a percentage)
  * @param annualizedReturn
  *   fraction, `totalReturn` annualized over the run's calendar span
  * @param maxDrawdown
  *   fraction, the largest peak-to-trough decline in the equity curve
  * @param sharpeAnnualized
  *   annualized Sharpe ratio, dimensionless
  * @param winRate
  *   fraction of round trips that were profitable; `None` when there were none
  * @param finalEquity
  *   currency amount, in the same units as `BacktestConfig.initialCash`
  * @param buyAndHoldReturn
  *   fraction: the return of holding from the first bar's open to the last bar's close instead of
  *   trading
  */
@JsonCodec
final case class BacktestMetrics(
  totalReturn: BigDecimal,
  annualizedReturn: BigDecimal,
  maxDrawdown: BigDecimal,
  sharpeAnnualized: BigDecimal,
  winRate: Option[BigDecimal],
  tradeCount: Int,
  roundTrips: Int,
  finalEquity: BigDecimal,
  buyAndHoldReturn: BigDecimal
)
