package st.btu.trading.pillar

import cats.Show
import io.estatico.newtype.macros.newtype
import tofu.logging.Loggable

package object alerts {

  @newtype final case class AlertRuleId(value: Long)

  object AlertRuleId {
    implicit val show: Show[AlertRuleId] = deriving
    implicit val loggable: Loggable[AlertRuleId] = deriving
  }

  @newtype final case class StrategyBindingId(value: Long)

  object StrategyBindingId {
    implicit val show: Show[StrategyBindingId] = deriving
    implicit val loggable: Loggable[StrategyBindingId] = deriving
  }
}
