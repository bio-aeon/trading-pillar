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

  /** One leg of a currency conversion: cash in one currency with no position. A conversion is two
    * exchange operations, one per currency, whose amounts have opposite signs.
    */
  case object Exchange extends OperationKind("exchange")

  /** A split or consolidation of the units of one instrument, with no cash: `quantity` is the
    * change of the position, positive for a forward split and negative for a reverse one. It has no
    * `price`, and its `amount` is zero.
    */
  case object Split extends OperationKind("split")

  /** One leg of an event that exchanges one instrument for others, such as a merger or a reverse
    * split onto a new instrument. `quantity` is the signed change of the leg's instrument: negative
    * for what is given up and positive for what is received. `price` is the market value per unit
    * where the broker reports one. `amount` is the cash the event pays (positive) or charges
    * (negative), which one leg of the group carries.
    */
  case object Conversion extends OperationKind("conversion")

  /** One leg of a spin-off, which gives the holder units of a new instrument while the holding in
    * the parent stays. `quantity` is the units of the new instrument received, positive, with no
    * `price` and no `amount`. The leg that names the parent instead has `quantity` zero.
    */
  case object Spinoff extends OperationKind("spinoff")

  /** Units of an instrument received as a dividend, with no cash: `quantity` is the units received,
    * positive, and `price` is the market value per unit where the broker reports one.
    */
  case object StockDividend extends OperationKind("stock_dividend")

  /** Units of an instrument, or cash, received as income outside a trade, such as a distribution or
    * an airdrop. With an instrument, `quantity` is the units received, positive, `price` is their
    * market value per unit where it is known, and `amount` is zero; without one, `amount` is the
    * cash received.
    */
  case object Reward extends OperationKind("reward")

  /** The settlement into cash of a futures position's gain or loss, which a broker books as often
    * as every day: `amount` is the cash credited (positive) or debited (negative) and `quantity` is
    * zero. It names the contract as its instrument where the broker says which one. Buying or
    * selling a contract moves no cash for its price, so the `amount` of a futures `buy` or `sell`
    * is zero, and a contract's gain or loss reaches the account as variation margin alone.
    */
  case object VariationMargin extends OperationKind("variation_margin")

  /** Anything else, including what a broker names but the bot does not classify; it carries no
    * change of position.
    */
  case object Other extends OperationKind("other")

  val values = findValues
}
