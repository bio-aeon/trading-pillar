package st.btu.trading.pillar.models.market

import scala.concurrent.duration.FiniteDuration

/** Failure raised by a `MarketDataSource` call. */
sealed abstract class MarketDataError(msg: String, cause: Option[Throwable])
    extends Exception(msg, cause.orNull)

object MarketDataError {

  /** `ticker` is not recognized by the source. */
  final case class NotFound(ticker: Ticker)
      extends MarketDataError(s"Ticker not found: ${ticker.value}", None)

  /** The source is rate-limiting requests.
    *
    * @param retryAfter
    *   how long to back off, if the source reported one
    */
  final case class RateLimited(retryAfter: Option[FiniteDuration])
      extends MarketDataError(
        "Rate limited by market data source" +
          retryAfter.fold("")(d => s" (retry after ${d.toSeconds}s)"),
        None
      )

  /** A transient failure (network, timeout) worth retrying. */
  final case class Transient(message: String, cause: Option[Throwable])
      extends MarketDataError(message, cause)

  /** Any other failure. */
  final case class Other(message: String, cause: Option[Throwable])
      extends MarketDataError(message, cause)
}
