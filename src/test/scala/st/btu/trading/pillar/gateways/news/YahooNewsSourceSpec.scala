package st.btu.trading.pillar.gateways.news

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import io.github.coinductive.yfinance4s.models.{Ticker as YfTicker, YFinanceError}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker}
import st.btu.trading.pillar.testkit.NewsFakes

import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class YahooNewsSourceSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")
  private val publishedAt = ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)

  private def item(title: String): NewsItem =
    NewsItem(title, Some("Reuters"), "https://example.com", Some(publishedAt))

  "YahooNewsSource" >> {
    "news" >> {

      "passes the ticker's value as the query and the count through to the API, unchanged" >> {
        for {
          api <- NewsFakes.yahooNewsApi(responses =
            Map(ticker.value -> IO.pure(List(item("Apple earnings"))))
          )
          source = new YahooNewsSource[IO](api)
          items <- source.news(ticker, 7)
          calls <- api.searchCalls
        } yield {
          items must_== List(item("Apple earnings"))
          calls must_== List((ticker.value, 7))
        }
      }

      "reports an unknown ticker as not found" >> {
        for {
          api <- NewsFakes.yahooNewsApi(responses =
            Map(ticker.value -> IO.raiseError(YFinanceError.TickerNotFound(YfTicker(ticker.value))))
          )
          source = new YahooNewsSource[IO](api)
          result <- source.news(ticker, 5).attempt
        } yield result must beLeft(MarketDataError.NotFound(ticker))
      }

      "keeps the retry-after hint when rate limited" >> {
        for {
          api <- NewsFakes.yahooNewsApi(responses =
            Map(ticker.value -> IO.raiseError(YFinanceError.RateLimited(Some(5.seconds))))
          )
          source = new YahooNewsSource[IO](api)
          result <- source.news(ticker, 5).attempt
        } yield result must beLeft(MarketDataError.RateLimited(Some(5.seconds)))
      }
    }
  }
}
