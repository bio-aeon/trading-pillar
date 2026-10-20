package st.btu.trading.pillar.gateways.yahoo

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import io.github.coinductive.yfinance4s.models.{Ticker as YfTicker, YFinanceError}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.{InstrumentInfo, MarketDataError, Source, Ticker}
import st.btu.trading.pillar.models.market.Ticker.value
import st.btu.trading.pillar.testkit.MarketDataFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZonedDateTime}
import scala.concurrent.duration.*

class YahooMarketDataSourceSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")

  private def usMetadata: YahooMetadata =
    YahooMetadata(
      symbol = ticker.value,
      exchangeName = "NMS",
      instrumentType = "EQUITY",
      currency = "USD",
      exchangeTimezoneName = "America/New_York",
      regularMarketTime = None,
      regularMarketPrice = Some(150.0)
    )

  private def quote(
    dt: ZonedDateTime,
    open: Double = 1,
    high: Double = 2,
    low: Double = 0.5,
    close: Double = 1.5,
    adjClose: Double = 1.5,
    volume: Long = 100
  ): YahooQuote = YahooQuote(dt, open, high, low, close, adjClose, volume)

  private def buildSource(api: YahooApi[IO]): IO[YahooMarketDataSource[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[YahooMarketDataSource[IO]])
    YahooMarketDataSource.create[IO, IO](api)
  }

  "YahooMarketDataSource" >> {
    "dailyBars" >> {
      "derives the bar date in the exchange timezone for a US ticker (13:30Z stays the same date)" >> {
        val dt = ZonedDateTime.parse("2024-01-10T13:30:00Z")
        for {
          api <- MarketDataFakes.yahooApi(
            charts = Map(ticker.value -> IO.pure(Some(YahooChart(List(quote(dt)), "USD")))),
            metadata = Map(ticker.value -> IO.pure(usMetadata))
          )
          source <- buildSource(api)
          bars <- source.dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield bars.map(_.date) must_== List(LocalDate.of(2024, 1, 10))
      }

      "derives the bar date in the exchange timezone when the UTC date differs (Auckland rolls to the next day)" >> {
        val dt = ZonedDateTime.parse("2024-01-09T22:00:00Z")
        val meta = usMetadata.copy(exchangeTimezoneName = "Pacific/Auckland")
        for {
          api <- MarketDataFakes.yahooApi(
            charts = Map(ticker.value -> IO.pure(Some(YahooChart(List(quote(dt)), "NZD")))),
            metadata = Map(ticker.value -> IO.pure(meta))
          )
          source <- buildSource(api)
          bars <- source.dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield bars.map(_.date) must_== List(LocalDate.of(2024, 1, 10))
      }

      "drops quotes with non-finite or non-positive prices, or negative volume" >> {
        val good = quote(ZonedDateTime.parse("2024-01-10T13:30:00Z"))
        val nonFiniteOpen = quote(ZonedDateTime.parse("2024-01-11T13:30:00Z"), open = Double.NaN)
        val zeroClose = quote(ZonedDateTime.parse("2024-01-12T13:30:00Z"), close = 0.0)
        val negativeVolume = quote(ZonedDateTime.parse("2024-01-13T13:30:00Z"), volume = -1L)
        for {
          api <- MarketDataFakes.yahooApi(
            charts = Map(
              ticker.value -> IO
                .pure(Some(YahooChart(List(good, nonFiniteOpen, zeroClose, negativeVolume), "USD")))
            ),
            metadata = Map(ticker.value -> IO.pure(usMetadata))
          )
          source <- buildSource(api)
          bars <- source.dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield bars.map(_.date) must_== List(LocalDate.of(2024, 1, 10))
      }

      "returns an empty list when the chart is empty" >> {
        for {
          api <- MarketDataFakes.yahooApi(
            charts = Map(ticker.value -> IO.pure(None)),
            metadata = Map(ticker.value -> IO.pure(usMetadata))
          )
          source <- buildSource(api)
          bars <- source.dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield bars must beEmpty
      }

      "reports an unknown ticker as not found" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata =
            Map(ticker.value -> IO.raiseError(YFinanceError.TickerNotFound(YfTicker(ticker.value))))
          )
          source <- buildSource(api)
          result <- source
            .dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
            .attempt
        } yield result must beLeft(MarketDataError.NotFound(ticker))
      }

      "keeps the retry-after hint when rate limited" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata =
            Map(ticker.value -> IO.raiseError(YFinanceError.RateLimited(Some(5.seconds))))
          )
          source <- buildSource(api)
          result <- source
            .dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
            .attempt
        } yield result must beLeft(MarketDataError.RateLimited(Some(5.seconds)))
      }
    }

    "dividends" >> {
      "derives the ex-date in the exchange timezone and scales the amount to 6 decimals" >> {
        val exDate = ZonedDateTime.parse("2024-01-10T13:30:00Z")
        for {
          api <- MarketDataFakes.yahooApi(
            metadata = Map(ticker.value -> IO.pure(usMetadata)),
            dividends = Map(ticker.value -> IO.pure(List(YahooDividend(exDate, 0.24, Some("USD")))))
          )
          source <- buildSource(api)
          events <- source.dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield {
          events must haveSize(1)
          events.head.exDate must_== LocalDate.of(2024, 1, 10)
          events.head.amount must_== BigDecimal("0.240000")
          events.head.currency must beSome(Currency("USD"))
          events.head.source must_== Source.Yahoo
        }
      }

      "derives the ex-date in the exchange timezone when it rolls to the next day (Auckland)" >> {
        val exDate = ZonedDateTime.parse("2024-01-09T22:00:00Z")
        val meta = usMetadata.copy(exchangeTimezoneName = "Pacific/Auckland")
        for {
          api <- MarketDataFakes.yahooApi(
            metadata = Map(ticker.value -> IO.pure(meta)),
            dividends = Map(ticker.value -> IO.pure(List(YahooDividend(exDate, 0.5, None))))
          )
          source <- buildSource(api)
          events <- source.dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield {
          events.head.exDate must_== LocalDate.of(2024, 1, 10)
          events.head.currency must beNone
        }
      }

      "returns an empty list when the API reports no dividends" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata = Map(ticker.value -> IO.pure(usMetadata)))
          source <- buildSource(api)
          events <- source.dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield events must beEmpty
      }

      "reports an unknown ticker as not found" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata =
            Map(ticker.value -> IO.raiseError(YFinanceError.TickerNotFound(YfTicker(ticker.value))))
          )
          source <- buildSource(api)
          result <- source
            .dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
            .attempt
        } yield result must beLeft(MarketDataError.NotFound(ticker))
      }
    }

    "latestQuote" >> {

      "quotes in the currency Yahoo reports, keeping a minor-unit marker such as GBp" >> {
        val pence = usMetadata.copy(currency = "GBp")
        for {
          api <- MarketDataFakes.yahooApi(metadata = Map(ticker.value -> IO.pure(pence)))
          source <- buildSource(api)
          quote <- source.latestQuote(ticker)
        } yield quote.map(_.currency) must beSome(Currency("GBp"))
      }
    }

    "instrumentInfo" >> {

      "describes the ticker from Yahoo's metadata, naming Yahoo as the source" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata = Map(ticker.value -> IO.pure(usMetadata)))
          source <- buildSource(api)
          info <- source.instrumentInfo(ticker)
        } yield info must beSome[InstrumentInfo].like { case i =>
          i.source must_== Source.Yahoo
          i.name must beNone
          i.instrumentType must_== "EQUITY"
          i.currency must_== Currency("USD")
          i.exchange must_== "NMS"
          i.timezone must_== "America/New_York"
          i.figi must beNone
          i.board must beNone
        }
      }
    }

    "metadata cache" >> {
      "serves metadata from the cache except for live quotes" >> {
        for {
          api <- MarketDataFakes.yahooApi(
            charts = Map(ticker.value -> IO.pure(Some(YahooChart(List.empty, "USD")))),
            metadata = Map(ticker.value -> IO.pure(usMetadata))
          )
          source <- buildSource(api)
          _ <- source.dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
          _ <- source.instrumentInfo(ticker)
          callsAfterCacheHits <- api.metadataCalls
          _ <- source.latestQuote(ticker)
          callsAfterBypass <- api.metadataCalls
        } yield {
          callsAfterCacheHits must haveSize(1)
          callsAfterBypass must haveSize(2)
        }
      }
    }
  }
}
