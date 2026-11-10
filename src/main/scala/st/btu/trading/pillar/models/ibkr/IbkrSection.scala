package st.btu.trading.pillar.models.ibkr

import enumeratum.values.{StringEnum, StringEnumEntry}

/** The part of an Activity Flex Query statement a row comes from. `value` is the string a consumer
  * persists.
  */
sealed abstract class IbkrSection(val value: String) extends StringEnumEntry

object IbkrSection extends StringEnum[IbkrSection] {

  /** A trade at the execution level: a fill of an order, or one leg of a currency conversion. */
  case object Trade extends IbkrSection("trade")

  /** A cash transaction at the detail level: a deposit, a dividend, a fee, interest or a tax. */
  case object CashTransaction extends IbkrSection("cash_transaction")

  /** A transfer of a position into or out of the account. */
  case object Transfer extends IbkrSection("transfer")

  /** One leg of a corporate action, such as a split or a merger, at the detail level. */
  case object CorporateAction extends IbkrSection("corporate_action")

  val values = findValues
}
