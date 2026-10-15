package st.btu.trading.pillar.gateways.tcs

import io.grpc.{Status, StatusRuntimeException}
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker}

// gRPC failure -> MarketDataError, shared by the Tinkoff adapters. `ticker` is the single ticker a
// call was made for, when there is one - a NOT_FOUND status only turns into
// MarketDataError.NotFound when a ticker is given to attach it to; a call made for something else,
// such as a FIGI, falls through to the generic Other mapping instead.
private[pillar] object TcsErrors {

  def isNotFound(e: Throwable): Boolean =
    e match {
      case status: StatusRuntimeException => status.getStatus.getCode == Status.Code.NOT_FOUND
      case _ => false
    }

  def toMarketDataError(ticker: Option[Ticker]): PartialFunction[Throwable, Throwable] = {
    case e: StatusRuntimeException =>
      (e.getStatus.getCode, ticker) match {
        case (Status.Code.NOT_FOUND, Some(known)) => MarketDataError.NotFound(known)
        case (Status.Code.RESOURCE_EXHAUSTED, _) => MarketDataError.RateLimited(None)
        case (Status.Code.UNAVAILABLE | Status.Code.DEADLINE_EXCEEDED, _) =>
          MarketDataError.Transient(statusMessage(e), Some(e))
        case _ => MarketDataError.Other(statusMessage(e), Some(e))
      }
    case other =>
      MarketDataError.Other(Option(other.getMessage).getOrElse(other.toString), Some(other))
  }

  private def statusMessage(e: StatusRuntimeException): String =
    Option(e.getStatus.getDescription).getOrElse(e.getStatus.getCode.toString)
}
