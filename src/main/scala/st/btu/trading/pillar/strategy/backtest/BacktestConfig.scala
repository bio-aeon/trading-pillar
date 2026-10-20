package st.btu.trading.pillar.strategy.backtest

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** Backtest and paper-simulation parameters.
  *
  * @param initialCash
  *   starting cash balance
  * @param commissionRate
  *   fraction of notional charged on every fill, e.g. `0.001` for 10 bps
  * @param slippageBps
  *   fill-price slippage in basis points, against the bar's open on both sides
  */
final case class BacktestConfig(
  initialCash: BigDecimal,
  commissionRate: BigDecimal,
  slippageBps: Int
)

object BacktestConfig {

  /** The JSON form a stored backtest run keeps its configuration in. Renaming a field breaks stored
    * runs.
    */
  implicit val codecForBacktestConfig: Codec.AsObject[BacktestConfig] = deriveCodec
}
