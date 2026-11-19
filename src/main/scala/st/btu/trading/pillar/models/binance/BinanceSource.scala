package st.btu.trading.pillar.models.binance

import enumeratum.values.{StringEnum, StringEnumEntry}

/** The endpoint of Binance's API a stored record was read from. `value` is the string a consumer
  * persists.
  */
sealed abstract class BinanceSource(val value: String) extends StringEnumEntry

object BinanceSource extends StringEnum[BinanceSource] {

  /** A fill of an order on a spot pair. */
  case object Trade extends BinanceSource("trade")

  /** A deposit of a coin or a stablecoin from outside Binance, or from another Binance user. */
  case object Deposit extends BinanceSource("deposit")

  /** A withdrawal of a coin or a stablecoin to outside Binance, or to another Binance user. */
  case object Withdrawal extends BinanceSource("withdrawal")

  /** A deposit of fiat money by bank transfer or another fiat channel. */
  case object FiatDeposit extends BinanceSource("fiat_deposit")

  /** A withdrawal of fiat money. */
  case object FiatWithdrawal extends BinanceSource("fiat_withdrawal")

  /** A purchase of a coin with fiat money, such as a card buy. */
  case object FiatBuy extends BinanceSource("fiat_buy")

  /** A sale of a coin for fiat money, which stays in the account. */
  case object FiatSell extends BinanceSource("fiat_sell")

  /** An exchange of one asset for another through Binance Convert. */
  case object Convert extends BinanceSource("convert")

  val values = findValues
}
