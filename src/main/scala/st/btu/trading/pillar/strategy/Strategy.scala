package st.btu.trading.pillar.strategy

import st.btu.trading.pillar.models.market.Bar

/** A trading rule that decides, bar by bar, whether to buy, sell or hold. */
trait Strategy {

  /** Stable identifier used to register, look up and record this strategy. */
  def id: StrategyId

  /** Human-readable name shown in replies and reports. */
  def name: String

  /** The parameter values this instance was built with; recorded with every backtest run. */
  def parameters: Map[String, String]

  /** Minimum history size (`StrategyContext.history.size`) required before [[onBar]] is called. */
  def warmupBars: Int

  /** Decides what to do on `bar`, given the context built up to and including it. */
  def onBar(ctx: StrategyContext, bar: Bar): List[StrategySignal]

  /** Reports how close the next bar is to triggering a signal, for a live "approaching" alert.
    *
    * @param bar
    *   the live, possibly provisional bar to judge against; not yet part of `ctx.history`, and not
    *   used by the backtest engine
    * @return
    *   `None` when no signal is close, or this strategy does not implement proximity
    */
  def proximity(ctx: StrategyContext, bar: Bar): Option[PreSignal] = None
}
