package st.btu.trading.pillar.gateways.yahoo

import io.github.coinductive.yfinance4s.models.YFinanceError
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker}

// YFinanceError -> MarketDataError, shared by every yfinance4s-backed adapter (YahooMarketDataSource,
// YahooNewsSource, YahooScreener). `ticker` is the single ticker a call was made for, when there is
// one - TickerNotFound/Delisted only turn into MarketDataError.NotFound when a ticker is given to
// attach it to; a screener call has no single ticker, so those two fall through to the generic
// Other mapping instead.
private[pillar] object YahooErrors {

  def toMarketDataError(ticker: Option[Ticker]): PartialFunction[Throwable, Throwable] = {
    case YFinanceError.TickerNotFound(_) if ticker.isDefined => MarketDataError.NotFound(ticker.get)
    case YFinanceError.Delisted(_) if ticker.isDefined => MarketDataError.NotFound(ticker.get)
    case YFinanceError.RateLimited(retryAfter) => MarketDataError.RateLimited(retryAfter)
    case YFinanceError.NetworkError(message, cause) => MarketDataError.Transient(message, cause)
    case YFinanceError.DataParseError(message, cause) => MarketDataError.Other(message, cause)
    case other =>
      MarketDataError.Other(Option(other.getMessage).getOrElse(other.toString), Some(other))
  }
}
