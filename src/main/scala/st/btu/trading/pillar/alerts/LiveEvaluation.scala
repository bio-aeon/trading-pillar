package st.btu.trading.pillar.alerts

import st.btu.trading.pillar.strategy.StrategySignal

/** The result of one [[LiveStrategyService.evaluate]] call.
  *
  * @param signals
  *   the newly-closed bar's Buy/Sell signals (never Hold), independent of whether the matching
  *   alert in `alerts` is actually delivered or suppressed by a guard. Proximity- driven
  *   PreBuy/PreSell alerts never produce a signal here.
  */
final case class LiveEvaluation(alerts: List[Alert], signals: List[StrategySignal])
