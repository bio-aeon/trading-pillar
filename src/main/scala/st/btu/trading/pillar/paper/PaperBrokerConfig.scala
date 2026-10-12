package st.btu.trading.pillar.paper

/** Paper-fill simulation parameters.
  *
  * @param commissionRate
  *   fraction of notional charged on every fill, e.g. `0.0005` for 5 bps
  * @param slippageBps
  *   fill-price slippage in basis points against the quote price, buy up or sell down, the same
  *   convention as `BacktestConfig`
  */
final case class PaperBrokerConfig(commissionRate: BigDecimal, slippageBps: Int)
