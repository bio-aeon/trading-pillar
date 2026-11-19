package st.btu.trading.pillar.models.binance

/** Why an API key cannot be used: the bot reads accounts and never trades, so it refuses a key that
  * could do more than read. The message names the permissions and never the key.
  */
sealed abstract class BinanceKeyError(message: String) extends Exception(message)

object BinanceKeyError {

  /** The key may not read the account. */
  case object ReadingNotEnabled
      extends BinanceKeyError("The Binance API key is not allowed to read the account")

  /** The key is allowed more than reading.
    *
    * @param flags
    *   the names of the permission flags that are set, as Binance names them, such as
    *   `enableWithdrawals`
    */
  final case class MoreThanReading(flags: List[String])
      extends BinanceKeyError(
        s"The Binance API key can do more than read: ${flags.mkString(", ")}; " +
          "create a key with Enable Reading only"
      )
}
