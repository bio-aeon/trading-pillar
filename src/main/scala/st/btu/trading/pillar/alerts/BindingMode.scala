package st.btu.trading.pillar.alerts

import enumeratum.values.*

/** Whether a [[StrategyBinding]] only alerts (`Signal`) or also trades on paper (`Paper`). */
sealed abstract class BindingMode(val value: String) extends StringEnumEntry

object BindingMode extends StringEnum[BindingMode] {

  /** Live evaluation only delivers alerts through `AlertDispatcher`, unchanged by paper mode. */
  case object Signal extends BindingMode("signal")

  /** Every Buy/Sell signal is additionally executed through a paper broker. */
  case object Paper extends BindingMode("paper")

  val values = findValues
}
