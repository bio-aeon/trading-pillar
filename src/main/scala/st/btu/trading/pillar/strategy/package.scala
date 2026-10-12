package st.btu.trading.pillar

import cats.Show
import io.estatico.newtype.macros.newtype
import tofu.logging.Loggable

package object strategy {

  @newtype final case class StrategyId(value: String)

  object StrategyId {
    implicit val show: Show[StrategyId] = deriving
    implicit val loggable: Loggable[StrategyId] = deriving
  }

  @newtype final case class BacktestRunId(value: Long)
}
