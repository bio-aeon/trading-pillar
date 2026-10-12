package st.btu.trading.pillar.gateways.yahoo

import io.github.coinductive.yfinance4s.models.{Ticker as YfTicker, YFinanceError}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker}

class YahooErrorsSpec extends Specification {

  private val ticker = Ticker("AAPL")
  private val yfTicker = YfTicker("AAPL")

  "YahooErrors" >> {
    "toMarketDataError" >> {

      "maps an unknown or delisted ticker to not-found when a ticker is given" >> {
        val mapper = YahooErrors.toMarketDataError(Some(ticker))
        mapper(YFinanceError.TickerNotFound(yfTicker)) must_== MarketDataError.NotFound(ticker)
        mapper(YFinanceError.Delisted(yfTicker)) must_== MarketDataError.NotFound(ticker)
      }

      "falls through to the generic mapping when no ticker is given" >> {
        val mapper = YahooErrors.toMarketDataError(None)
        mapper(YFinanceError.TickerNotFound(yfTicker)) must beAnInstanceOf[MarketDataError.Other]
        mapper(YFinanceError.Delisted(yfTicker)) must beAnInstanceOf[MarketDataError.Other]
      }

      "maps rate-limit, network and parse errors the same regardless of ticker" >> {
        val withTicker = YahooErrors.toMarketDataError(Some(ticker))
        val withoutTicker = YahooErrors.toMarketDataError(None)
        val cause = new RuntimeException("boom")

        withTicker(YFinanceError.RateLimited(None)) must_== MarketDataError.RateLimited(None)
        withoutTicker(YFinanceError.RateLimited(None)) must_== MarketDataError.RateLimited(None)

        withTicker(YFinanceError.NetworkError("net down", Some(cause))) must_==
          MarketDataError.Transient("net down", Some(cause))
        withoutTicker(YFinanceError.NetworkError("net down", Some(cause))) must_==
          MarketDataError.Transient("net down", Some(cause))

        withTicker(YFinanceError.DataParseError("bad json", None)) must_==
          MarketDataError.Other("bad json", None)
        withoutTicker(YFinanceError.DataParseError("bad json", None)) must_==
          MarketDataError.Other("bad json", None)
      }

      "maps any other throwable to a generic error, using its message" >> {
        val mapper = YahooErrors.toMarketDataError(Some(ticker))
        val boom = new RuntimeException("unexpected")
        mapper(boom) must_== MarketDataError.Other("unexpected", Some(boom))
      }
    }
  }
}
