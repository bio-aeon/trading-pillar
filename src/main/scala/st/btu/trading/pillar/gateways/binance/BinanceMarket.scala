package st.btu.trading.pillar.gateways.binance

import st.btu.trading.pillar.models.binance.*

import java.time.{Instant, LocalDate}

/** Reads the market data that Binance publishes, through its REST API, with no key. No call here
  * reads an account, places an order or moves funds.
  *
  * Every call can fail with a [[BinanceError]].
  */
trait BinanceMarket[F[_]] {

  /** Every spot pair Binance lists, in any trading status, with its base and quote asset. A pair
    * that Binance has delisted leaves the answer, and its trades can no longer be read.
    */
  def exchangeInfo: F[List[BinancePair]]

  /** The close of the one-minute bar of `symbol` that starts at `minute`, or `None` when the pair
    * had no bar in that minute or Binance does not know it.
    */
  def minuteClose(symbol: BinanceSymbol, minute: Instant): F[Option[BigDecimal]]

  /** The daily bars of `symbol` that open on the UTC days from `from` to `to`, both included,
    * oldest first. A day the pair had no bar on, such as one before its listing, has none. The bar
    * of a day that has not ended yet holds the trades so far.
    *
    * @return
    *   a failed `F` with a [[BinanceError.Refused]] with code [[BinanceError.InvalidSymbol]] when
    *   Binance does not know the pair
    */
  def dailyBars(symbol: BinanceSymbol, from: LocalDate, to: LocalDate): F[List[BinanceDailyBar]]

  /** The price of the latest trade of `symbol`, or `None` when Binance does not know the pair. */
  def lastPrice(symbol: BinanceSymbol): F[Option[BigDecimal]]
}
