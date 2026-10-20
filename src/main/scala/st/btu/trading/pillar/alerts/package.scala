package st.btu.trading.pillar

import cats.Show
import tofu.logging.Loggable

/** Alert identifier types: `X(value)` wraps a value unchecked and `x.value` unwraps it. */
package object alerts {

  /** The id a storage gives a stored price-threshold rule, a [[PriceCrossed]]. */
  opaque type AlertRuleId = Long

  object AlertRuleId {
    def apply(value: Long): AlertRuleId = value
    extension (ruleId: AlertRuleId) def value: Long = ruleId
    implicit val show: Show[AlertRuleId] = Show.catsShowForLong
    implicit val loggable: Loggable[AlertRuleId] = Loggable.longLoggable
  }

  /** The id a storage gives a stored [[StrategyBinding]]. */
  opaque type StrategyBindingId = Long

  object StrategyBindingId {
    def apply(value: Long): StrategyBindingId = value
    extension (bindingId: StrategyBindingId) def value: Long = bindingId
    implicit val show: Show[StrategyBindingId] = Show.catsShowForLong
    implicit val loggable: Loggable[StrategyBindingId] = Loggable.longLoggable
  }
}
