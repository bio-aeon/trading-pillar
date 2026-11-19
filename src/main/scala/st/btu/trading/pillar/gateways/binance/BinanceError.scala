package st.btu.trading.pillar.gateways.binance

/** Why a call to Binance failed. No message carries the query string of a request, its signature or
  * the key it was made with.
  */
sealed abstract class BinanceError(message: String) extends Exception(message)

object BinanceError {

  /** Binance's code for a pair it does not know, such as one it has delisted. */
  val InvalidSymbol: Int = -1121

  /** Binance's code for a request whose timestamp is outside its receive window, which a clock that
    * has drifted from Binance's causes.
    */
  val TimestampOutsideRecvWindow: Int = -1021

  /** Binance refused the request.
    *
    * @param code
    *   Binance's error code, such as -2015 for a key that is invalid, not allowed from this IP or
    *   lacks the permission
    * @param reason
    *   Binance's error message
    */
  final case class Refused(code: Int, reason: String)
      extends BinanceError(s"Binance refused the request ($code): $reason")

  /** Binance kept answering that the call should be made again later, until the attempts ran out.
    *
    * @param status
    *   the HTTP status of the last answer, 429 or a 5xx
    * @param attempts
    *   how many times the call was made
    */
  final case class RetriesExhausted(status: Int, attempts: Int)
      extends BinanceError(s"Binance still answered HTTP $status after $attempts attempts")

  /** Binance has banned the IP address after calls that ignored its rate limit (HTTP 418). Calls
    * fail until the ban ends, which waiting a little does not bring about.
    *
    * @param endpoint
    *   the path of the call that was answered with the ban
    */
  final case class Banned(endpoint: String)
      extends BinanceError(s"Binance has banned the IP address (HTTP 418) on $endpoint")

  /** The answer is none of those the endpoint documents, or its HTTP status is a failure that
    * waiting does not change, such as 403.
    */
  final case class UnexpectedResponse(detail: String)
      extends BinanceError(s"Unexpected response from Binance: $detail")

  /** No answer came: the call failed on the way, by a refused connection, a timeout or a dropped
    * connection, until the attempts ran out.
    *
    * @param detail
    *   what failed, built from the cause of the failure and never from the request
    */
  final case class Unreachable(detail: String)
      extends BinanceError(s"Binance could not be reached: $detail")
}
