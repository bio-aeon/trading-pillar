package st.btu.trading.pillar.strategy.backtest

import st.btu.trading.pillar.strategy.OpenPosition

// Result of BacktestEngine.fillOne: cash/position always carry the new accounting state (fillOne
// returning cash/position unchanged is "nothing happened" - still a value here, not an absence),
// and exactly one of the other two fields ever applies - a shape a plain tuple of options cannot
// express or have checked exhaustively.
private[backtest] sealed trait FillOutcome

private[backtest] object FillOutcome {
  final case class Filled(cash: BigDecimal, position: Option[OpenPosition], trade: Trade)
      extends FillOutcome

  final case class Skipped(cash: BigDecimal, position: Option[OpenPosition], skipped: SkippedSignal)
      extends FillOutcome

  final case class NoOp(cash: BigDecimal, position: Option[OpenPosition]) extends FillOutcome
}
