package st.btu.trading.pillar.gateways.binance

import io.circe.JsonObject
import st.btu.trading.pillar.models.binance.*

import java.time.Instant

/** Reads a Binance account's history and the market data that values it, through Binance's REST API
  * with a key that can read and do nothing else. No call here places an order, moves funds or
  * changes the account.
  *
  * Every call can fail with a [[BinanceError]]. Records come back as the JSON objects Binance sent,
  * so that they are stored as received; the other answers come back as typed values.
  */
trait BinanceApi[F[_]] {

  /** What the API key may do, as Binance reports it for the key. */
  def apiRestrictions: F[BinanceKeyPermissions]

  /** Every spot pair Binance lists, in any trading status, with its base and quote asset. A pair
    * that Binance has delisted leaves the answer, and its trades can no longer be read.
    */
  def exchangeInfo: F[List[BinancePair]]

  /** The pairs Binance has announced it will delist. */
  def delistSchedule: F[List[BinanceSymbol]]

  /** The name of each coin Binance lists, by its asset code. */
  def coins: F[Map[BinanceAsset, String]]

  /** One page of at most [[BinanceApi.TradesPageSize]] of the account's trades on `symbol`, with
    * ids from `fromId` upwards, oldest first. A page of fewer trades is the last. A pair Binance no
    * longer knows gives no trades.
    */
  def myTrades(symbol: BinanceSymbol, fromId: BinanceTradeId): F[List[JsonObject]]

  /** The account's crypto deposits made from `from` to `to`, both included, whatever their status.
    * The window must be shorter than 90 days.
    */
  def deposits(from: Instant, to: Instant): F[List[JsonObject]]

  /** The account's crypto withdrawals applied for from `from` to `to`, both included, whatever
    * their status. The window must be shorter than 90 days.
    */
  def withdrawals(from: Instant, to: Instant): F[List[JsonObject]]

  /** The account's fiat deposits or withdrawals ordered from `from` to `to`, both included. */
  def fiatOrders(direction: FiatOrderDirection, from: Instant, to: Instant): F[List[JsonObject]]

  /** The account's purchases or sales of coins for fiat money made from `from` to `to`, both
    * included.
    */
  def fiatPayments(direction: FiatPaymentDirection, from: Instant, to: Instant): F[List[JsonObject]]

  /** The account's Convert orders created from `from` to `to`, both included, whatever their
    * status. The window must not be longer than 30 days. A window with more orders than one answer
    * holds is read as two halves.
    */
  def convertTradeFlow(from: Instant, to: Instant): F[List[JsonObject]]

  /** The close of the one-minute bar of `symbol` that starts at `minute`, or `None` when the pair
    * had no bar in that minute or Binance does not know it. Needs no key.
    */
  def minuteClose(symbol: BinanceSymbol, minute: Instant): F[Option[BigDecimal]]
}

object BinanceApi {

  /** The most trades one call of `myTrades` returns. */
  val TradesPageSize: Int = 1000
}

/** The direction of a fiat order. `code` is the `transactionType` Binance takes. */
enum FiatOrderDirection(private[binance] val code: Int) {

  /** Money paid into the account. */
  case Deposit extends FiatOrderDirection(0)

  /** Money paid out of the account. */
  case Withdrawal extends FiatOrderDirection(1)
}

/** The direction of a payment for coins made with fiat money. `code` is the `transactionType`
  * Binance takes.
  */
enum FiatPaymentDirection(private[binance] val code: Int) {

  /** A purchase of a coin with fiat money. */
  case Buy extends FiatPaymentDirection(0)

  /** A sale of a coin for fiat money. */
  case Sell extends FiatPaymentDirection(1)
}
