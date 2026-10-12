package st.btu.trading.pillar.strategy.backtest

import io.circe.generic.JsonCodec

/** Backtest and paper-simulation parameters.
  *
  * @param initialCash
  *   starting cash balance
  * @param commissionRate
  *   fraction of notional charged on every fill, e.g. `0.001` for 10 bps
  * @param slippageBps
  *   fill-price slippage in basis points, against the bar's open on both sides
  */
@JsonCodec
final case class BacktestConfig(
  initialCash: BigDecimal,
  commissionRate: BigDecimal,
  slippageBps: Int
)
