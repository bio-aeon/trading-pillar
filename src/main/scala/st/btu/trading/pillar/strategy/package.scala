package st.btu.trading.pillar

import cats.Show
import tofu.logging.Loggable

/** Strategy identifier types: `X(value)` wraps a value unchecked and `x.value` unwraps it. */
package object strategy {

  /** The stable id a strategy is registered, bound and recorded under, such as `ma-cross-50-200`.
    */
  opaque type StrategyId = String

  object StrategyId {
    def apply(value: String): StrategyId = value
    extension (strategyId: StrategyId) def value: String = strategyId
    implicit val show: Show[StrategyId] = Show.catsShowForString
    implicit val loggable: Loggable[StrategyId] = Loggable.stringValue
  }

  /** The id a storage gives a stored backtest run. */
  opaque type BacktestRunId = Long

  object BacktestRunId {
    def apply(value: Long): BacktestRunId = value
    extension (runId: BacktestRunId) def value: Long = runId
  }
}
