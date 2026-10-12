package st.btu.trading.pillar.alerts

import enumeratum.values.*

/** The kind of event an [[Alert]] reports; its string value is stored with each alert record. */
sealed abstract class AlertKind(val value: String) extends StringEnumEntry

object AlertKind extends StringEnum[AlertKind] {
  case object PreTarget extends AlertKind("pre_target")
  case object TargetHit extends AlertKind("target_hit")
  case object StrategyBuy extends AlertKind("strategy_buy")
  case object StrategySell extends AlertKind("strategy_sell")
  case object StrategyPreBuy extends AlertKind("strategy_pre_buy")
  case object StrategyPreSell extends AlertKind("strategy_pre_sell")
  case object System extends AlertKind("system")
  case object AllocationDrift extends AlertKind("allocation_drift")

  val values = findValues
}
