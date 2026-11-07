package st.btu.trading.pillar.models.domain

import enumeratum.values.{StringEnum, StringEnumEntry}

/** How far an operation has got at the broker. `value` is the string a consumer persists. */
sealed abstract class OperationState(val value: String) extends StringEnumEntry

object OperationState extends StringEnum[OperationState] {

  /** The broker carried the operation out. Only executed operations count towards positions, cash
    * and tax.
    */
  case object Executed extends OperationState("executed")

  /** The broker has not finished the operation, for example a limit order still waiting to fill. */
  case object Pending extends OperationState("pending")

  /** The operation was cancelled and did not happen. */
  case object Canceled extends OperationState("canceled")

  val values = findValues
}
