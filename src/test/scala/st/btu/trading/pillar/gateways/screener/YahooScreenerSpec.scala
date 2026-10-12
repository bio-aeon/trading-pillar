package st.btu.trading.pillar.gateways.screener

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import io.github.coinductive.yfinance4s.models.{
  PredefinedScreen as YfPredefinedScreen,
  YFinanceError
}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker}
import st.btu.trading.pillar.testkit.ScreenerFakes

import scala.concurrent.duration.*

class YahooScreenerSpec extends Specification with CatsEffect {

  private def row(symbol: String): ScreenerRow =
    ScreenerRow(Ticker(symbol), Some(symbol), Some(BigDecimal(100)), Some(BigDecimal(1.5)))

  "YahooScreener" >> {
    "predefined" >> {

      "predefined screens" >> {

        "maps Gainers to yfinance4s DayGainers and passes the count through" >> {
          for {
            api <- ScreenerFakes.yahooScreenerApi(responses =
              Map(YfPredefinedScreen.DayGainers -> IO.pure(List(row("AAPL"))))
            )
            screener = new YahooScreener[IO](api)
            rows <- screener.predefined(PredefinedScreen.Gainers, 15)
            calls <- api.predefinedCalls
          } yield {
            rows must_== List(row("AAPL"))
            calls must_== List((YfPredefinedScreen.DayGainers, 15))
          }
        }

        "maps Losers to yfinance4s DayLosers" >> {
          for {
            api <- ScreenerFakes.yahooScreenerApi(responses =
              Map(YfPredefinedScreen.DayLosers -> IO.pure(List(row("GME"))))
            )
            screener = new YahooScreener[IO](api)
            rows <- screener.predefined(PredefinedScreen.Losers, 10)
          } yield rows must_== List(row("GME"))
        }

        "maps Active to yfinance4s MostActives" >> {
          for {
            api <- ScreenerFakes.yahooScreenerApi(responses =
              Map(YfPredefinedScreen.MostActives -> IO.pure(List(row("TSLA"))))
            )
            screener = new YahooScreener[IO](api)
            rows <- screener.predefined(PredefinedScreen.Active, 10)
          } yield rows must_== List(row("TSLA"))
        }
      }

      "keeps the retry-after hint when rate limited" >> {
        for {
          api <- ScreenerFakes.yahooScreenerApi(responses =
            Map(
              YfPredefinedScreen.DayGainers -> IO
                .raiseError(YFinanceError.RateLimited(Some(5.seconds)))
            )
          )
          screener = new YahooScreener[IO](api)
          result <- screener.predefined(PredefinedScreen.Gainers, 10).attempt
        } yield result must beLeft(MarketDataError.RateLimited(Some(5.seconds)))
      }
    }
  }
}
