package st.btu.trading.pillar.models.binance

/** What an API key may do, as Binance reports it for the key itself.
  *
  * @param ipRestricted
  *   whether the key works only from the IP addresses its owner listed
  * @param enableReading
  *   whether the key may read the account
  * @param otherFlags
  *   every other permission flag the answer carries, by name: each boolean field whose name starts
  *   with `enable` or `permits`, except `enableFixReadOnly`, which only says the key may read over
  *   the FIX protocol. A flag that is true grants the key more than reading, among them trading,
  *   withdrawals, transfers, margin, futures and options.
  */
final case class BinanceKeyPermissions(
  ipRestricted: Boolean,
  enableReading: Boolean,
  otherFlags: Map[String, Boolean]
) {

  /** The names of the flags besides reading that are true, in alphabetical order. */
  def grantedBeyondReading: List[String] =
    otherFlags.collect { case (name, true) => name }.toList.sorted

  /** Accepts a key that may read and whose every other permission flag is false or absent, and
    * otherwise gives the reason it is refused. A flag that is not known yet counts as a permission,
    * so a key that can do something new is refused too.
    */
  def readOnly: Either[BinanceKeyError, Unit] = {
    val granted = grantedBeyondReading
    if (granted.nonEmpty) Left(BinanceKeyError.MoreThanReading(granted))
    else if (!enableReading) Left(BinanceKeyError.ReadingNotEnabled)
    else Right(())
  }
}
