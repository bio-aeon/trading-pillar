package st.btu.trading.pillar.models.domain

import enumeratum.values.{StringEnum, StringEnumEntry}

/** What an operation did to an account, in terms shared by every broker. `value` is the string a
  * consumer persists.
  */
sealed abstract class OperationKind(val value: String) extends StringEnumEntry

object OperationKind extends StringEnum[OperationKind] {

  /** Units of an instrument bought: the position grows and cash falls. */
  case object Buy extends OperationKind("buy")

  /** Units of an instrument sold: the position falls and cash grows. */
  case object Sell extends OperationKind("sell")

  /** Units of an instrument transferred into the account: the position grows, cash is untouched. */
  case object SecurityIn extends OperationKind("security_in")

  /** Units of an instrument transferred out of the account: the position falls, cash is untouched.
    */
  case object SecurityOut extends OperationKind("security_out")

  /** Cash paid into the account from outside. */
  case object Deposit extends OperationKind("deposit")

  /** Cash taken out of the account. */
  case object Withdrawal extends OperationKind("withdrawal")

  /** A dividend paid on a held instrument. */
  case object Dividend extends OperationKind("dividend")

  /** A coupon paid on a held bond. */
  case object Coupon extends OperationKind("coupon")

  /** Interest earned on cash, such as the income of an overnight placement. */
  case object Interest extends OperationKind("interest")

  /** A charge by the broker or an exchange: a commission, a service fee, a penalty or a duty. */
  case object Fee extends OperationKind("fee")

  /** Tax withheld by the broker, or the refund or correction of tax withheld earlier. */
  case object Tax extends OperationKind("tax")

  /** Anything else, including what a broker names but the bot does not classify; it carries no
    * change of position.
    */
  case object Other extends OperationKind("other")

  val values = findValues
}
