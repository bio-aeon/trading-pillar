package st.btu.trading.pillar.paper

import enumeratum.values.*

/** Lifecycle state of a [[PaperOrder]]. */
sealed abstract class PaperOrderStatus(val value: String) extends StringEnumEntry

object PaperOrderStatus extends StringEnum[PaperOrderStatus] {
  case object Pending extends PaperOrderStatus("pending")
  case object Filled extends PaperOrderStatus("filled")
  case object Rejected extends PaperOrderStatus("rejected")

  val values = findValues
}
