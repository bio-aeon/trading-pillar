package st.btu.trading.pillar.models

import cats.Show
import tofu.logging.Loggable

/** Binance identifier and credential types: `X(value)` wraps a value unchecked and `x.value`
  * unwraps it.
  */
package object binance {

  /** A Binance API key, sent in the `X-MBX-APIKEY` header of signed requests. It is a secret: its
    * `Show` and `Loggable` instances print a mask, so logging a key does not reveal it. Take
    * `value` only to build a request.
    */
  opaque type ApiKey = String

  object ApiKey {
    private val Mask = "ApiKey(***)"

    def apply(value: String): ApiKey = value
    extension (apiKey: ApiKey) def value: String = apiKey
    implicit val show: Show[ApiKey] = Show.show(_ => Mask)
    implicit val loggable: Loggable[ApiKey] = Loggable.stringValue.contramap(_ => Mask)
  }

  /** A Binance secret key, which signs requests with HMAC-SHA256. It is a secret: its `Show` and
    * `Loggable` instances print a mask, so logging a key does not reveal it. Take `value` only to
    * sign a request.
    */
  opaque type SecretKey = String

  object SecretKey {
    private val Mask = "SecretKey(***)"

    def apply(value: String): SecretKey = value
    extension (secretKey: SecretKey) def value: String = secretKey
    implicit val show: Show[SecretKey] = Show.show(_ => Mask)
    implicit val loggable: Loggable[SecretKey] = Loggable.stringValue.contramap(_ => Mask)
  }

  /** A coin or a currency as Binance writes it: `BTC`, `USDT`, `EUR`. */
  opaque type BinanceAsset = String

  object BinanceAsset {
    def apply(value: String): BinanceAsset = value
    extension (asset: BinanceAsset) def value: String = asset
    implicit val show: Show[BinanceAsset] = Show.catsShowForString
    implicit val loggable: Loggable[BinanceAsset] = Loggable.stringValue
    implicit val ordering: Ordering[BinanceAsset] = Ordering.String
  }

  /** A trading pair as Binance names it: its base asset followed by its quote asset, such as
    * `BTCUSDT`.
    */
  opaque type BinanceSymbol = String

  object BinanceSymbol {
    def apply(value: String): BinanceSymbol = value

    /** The pair that trades `base` against `quote`. */
    def of(base: BinanceAsset, quote: BinanceAsset): BinanceSymbol = base.value + quote.value

    extension (symbol: BinanceSymbol) def value: String = symbol
    implicit val show: Show[BinanceSymbol] = Show.catsShowForString
    implicit val loggable: Loggable[BinanceSymbol] = Loggable.stringValue
    implicit val ordering: Ordering[BinanceSymbol] = Ordering.String
  }

  /** The id of a stored Binance record within its source: for a trade the symbol and the trade id,
    * since trade ids are unique only within a pair.
    */
  opaque type BinanceRecordId = String

  object BinanceRecordId {
    def apply(value: String): BinanceRecordId = value
    extension (recordId: BinanceRecordId) def value: String = recordId
    implicit val show: Show[BinanceRecordId] = Show.catsShowForString
    implicit val loggable: Loggable[BinanceRecordId] = Loggable.stringValue
  }

  /** The id Binance gives a trade, unique and increasing within its pair. */
  opaque type BinanceTradeId = Long

  object BinanceTradeId {

    /** The id to read a pair's trades from when none of them is known: Binance counts from 0. */
    val Start: BinanceTradeId = 0L

    def apply(value: Long): BinanceTradeId = value
    extension (tradeId: BinanceTradeId) {
      def value: Long = tradeId

      /** The id that follows this one, from which the next trades of the pair are read. */
      def next: BinanceTradeId = tradeId + 1
    }
    implicit val show: Show[BinanceTradeId] = Show.fromToString
    implicit val loggable: Loggable[BinanceTradeId] = Loggable.longLoggable
    implicit val ordering: Ordering[BinanceTradeId] = Ordering.Long
  }
}
