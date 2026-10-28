package st.btu.trading.pillar.gateways.yahoo

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import io.github.coinductive.yfinance4s.models.{Ticker as YfTicker, YFinanceError}
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.market.Ticker.value
import st.btu.trading.pillar.testkit.MarketDataFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZonedDateTime}
import scala.concurrent.duration.*

class YahooMarketDataSourceSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")

  private val vod = Ticker("VOD")
  private val vodafone = Ticker("VODAFONE")
  private val londonName = "VOD.L"
  private val vodL = YahooSymbol(londonName)

  private val tradingDay = LocalDate.of(2024, 1, 10)
  private val tradingTime = ZonedDateTime.parse("2024-01-10T12:00:00Z")

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
    volume: Long = 100,
    inProgress: Boolean = false
  ): YahooQuote = YahooQuote(dt, open, high, low, close, adjClose, volume, inProgress)

  // Yahoo's description of VOD.L, quoted in `currency` (pence by default).
  private def londonMetadata(currency: String = "GBp"): YahooMetadata =
    YahooMetadata(
      symbol = londonName,
      exchangeName = "LSE",
      instrumentType = "EQUITY",
      currency = currency,
      exchangeTimezoneName = "Europe/London",
      regularMarketTime = None,
      regularMarketPrice = Some(7234.5)
    )

  // An API that knows VOD.L and nothing under the ticker VOD, so a lookup by the ticker fails.
  private def londonApi(
    currency: String = "GBp",
    quotes: List[YahooQuote] = List(quote(tradingTime)),
    dividends: List[YahooDividend] = Nil
  ): IO[MarketDataFakes.FakeYahooApi] =
    MarketDataFakes.yahooApi(
      charts = Map(londonName -> IO.pure(Some(YahooChart(quotes, currency)))),
      metadata = Map(londonName -> IO.pure(londonMetadata(currency))),
      dividends = Map(londonName -> IO.pure(dividends))
    )

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

      "leaves out the bar of a session still in progress" >> {
        val closedDay = quote(ZonedDateTime.parse("2024-01-10T14:30:00Z"))
        val openDay = quote(ZonedDateTime.parse("2024-01-11T14:30:00Z"), inProgress = true)
        for {
          api <- MarketDataFakes.yahooApi(
            charts =
              Map(ticker.value -> IO.pure(Some(YahooChart(List(closedDay, openDay), "USD")))),
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

      "quotes in the currency Yahoo reports, with the price at six decimals" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata = Map(ticker.value -> IO.pure(usMetadata)))
          source <- buildSource(api)
          quote <- source.latestQuote(ticker)
        } yield quote must beSome[Quote].like { case q =>
          q.price must_== BigDecimal("150")
          q.price.scale must_== 6
          q.currency must_== Currency("USD")
        }
      }
    }

    "instrumentInfo" >> {

      "describes the ticker from Yahoo's metadata, naming Yahoo as the source" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata = Map(ticker.value -> IO.pure(usMetadata)))
          source <- buildSource(api)
          info <- source.instrumentInfo(ticker)
        } yield info must beSome[InstrumentInfo].like { case i =>
          i.origin must_== SourceSetting.Yahoo(None)
          i.name must beNone
          i.instrumentType must_== "EQUITY"
          i.currency must_== Currency("USD")
          i.exchange must_== "NMS"
          i.timezone must_== "America/New_York"
          i.figi must beNone
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

    "onSymbol" >> {

      "looks up the symbol instead of the ticker, and reports under the ticker" >> {

        "for bars" >> {
          for {
            api <- londonApi()
            source <- buildSource(api)
            bars <- source.onSymbol(Some(vodL)).dailyBars(vod, tradingDay, tradingDay)
            charts <- api.dailyChartCalls
          } yield {
            charts must_== List(londonName)
            bars.map(_.ticker) must_== List(vod)
          }
        }

        "for the latest quote" >> {
          for {
            api <- londonApi()
            source <- buildSource(api)
            quote <- source.onSymbol(Some(vodL)).latestQuote(vod)
            metadataCalls <- api.metadataCalls
          } yield {
            metadataCalls must_== List(londonName)
            quote.map(_.ticker) must beSome(vod)
          }
        }

        "for the instrument description" >> {
          for {
            api <- londonApi()
            source <- buildSource(api)
            info <- source.onSymbol(Some(vodL)).instrumentInfo(vod)
            metadataCalls <- api.metadataCalls
          } yield {
            metadataCalls must_== List(londonName)
            info.map(_.ticker) must beSome(vod)
            info.map(_.exchange) must beSome("LSE")
          }
        }

        "for dividends" >> {
          for {
            api <- londonApi(dividends = List(YahooDividend(tradingTime, 12.5, Some("GBp"))))
            source <- buildSource(api)
            events <- source.onSymbol(Some(vodL)).dividends(vod, tradingDay, tradingDay)
            dividendsCalls <- api.dividendsCalls
          } yield {
            dividendsCalls must_== List(londonName)
            events.map(_.ticker) must_== List(vod)
          }
        }
      }

      "looks the ticker up as given when no symbol is named" >> {
        for {
          api <- MarketDataFakes.yahooApi(metadata = Map(vod.value -> IO.pure(usMetadata)))
          source <- buildSource(api)
          _ <- source.onSymbol(None).instrumentInfo(vod)
          metadataCalls <- api.metadataCalls
        } yield metadataCalls must_== List(vod.value)
      }

      "origin" >> {

        "is Yahoo under the symbol the view was opened for" >> {
          for {
            api <- londonApi()
            source <- buildSource(api)
          } yield source.onSymbol(Some(vodL)).origin must_== SourceSetting.Yahoo(Some(vodL))
        }

        "is what the instrument description records" >> {
          for {
            api <- londonApi()
            source <- buildSource(api)
            view = source.onSymbol(Some(vodL))
            info <- view.instrumentInfo(vod)
          } yield info.map(_.origin) must beSome(view.origin)
        }
      }

      "metadata cache" >> {

        "is shared by the tickers set to one symbol" >> {
          for {
            api <- londonApi()
            source <- buildSource(api)
            _ <- source.onSymbol(Some(vodL)).instrumentInfo(vod)
            _ <- source.onSymbol(Some(vodL)).instrumentInfo(vodafone)
            metadataCalls <- api.metadataCalls
          } yield metadataCalls must_== List(londonName)
        }

        "keeps a ticker apart from the same ticker set to a symbol" >> {
          for {
            api <- MarketDataFakes.yahooApi(metadata =
              Map(
                vod.value -> IO.pure(usMetadata.copy(symbol = vod.value)),
                londonName -> IO.pure(londonMetadata("GBP"))
              )
            )
            source <- buildSource(api)
            asGiven <- source.instrumentInfo(vod)
            underSymbol <- source.onSymbol(Some(vodL)).instrumentInfo(vod)
            metadataCalls <- api.metadataCalls
          } yield {
            metadataCalls must_== List(vod.value, londonName)
            asGiven.map(_.currency) must beSome(Currency("USD"))
            underSymbol.map(_.currency) must beSome(Currency("GBP"))
          }
        }
      }
    }

    "minor units" >> {

      "bars" >> {

        "are reported in the major currency, with prices divided by 100 and volume unchanged" >> {
          val pence = quote(
            tradingTime,
            open = 7234.5,
            high = 7300,
            low = 7200.25,
            close = 7250.75,
            adjClose = 7100,
            volume = 1234
          )
          for {
            api <- londonApi(quotes = List(pence))
            source <- buildSource(api)
            bars <- source.onSymbol(Some(vodL)).dailyBars(vod, tradingDay, tradingDay)
          } yield bars must_== List(
            Bar(
              vod,
              tradingDay,
              BigDecimal("72.345"),
              BigDecimal("73"),
              BigDecimal("72.0025"),
              BigDecimal("72.5075"),
              BigDecimal("71"),
              1234,
              Source.Yahoo
            )
          )
        }

        "are left alone when the currency is the major one, pounds written in capitals" >> {
          for {
            api <- londonApi(
              currency = "GBP",
              quotes = List(quote(tradingTime, open = 72.5, close = 73.25))
            )
            source <- buildSource(api)
            bars <- source.onSymbol(Some(vodL)).dailyBars(vod, tradingDay, tradingDay)
          } yield bars.map(bar => (bar.open, bar.close)) must_==
            List((BigDecimal("72.5"), BigDecimal("73.25")))
        }
      }

      "quote and instrument description" >> {
        Fragments.foreach(List("GBp" -> "GBP", "ZAc" -> "ZAR", "ILA" -> "ILS")) {
          case (minor, major) =>
            s"report $minor as $major, with the price divided by 100" >> {
              for {
                api <- londonApi(currency = minor)
                source <- buildSource(api)
                view = source.onSymbol(Some(vodL))
                quote <- view.latestQuote(vod)
                info <- view.instrumentInfo(vod)
              } yield {
                quote.map(q => (q.price, q.currency)) must beSome(
                  (BigDecimal("72.345"), Currency(major))
                )
                info.map(_.currency) must beSome(Currency(major))
              }
            }
        }
      }

      "dividends" >> {

        "convert an amount in a minor unit of their own and name the major currency" >> {
          for {
            api <- londonApi(
              currency = "USD",
              dividends = List(YahooDividend(tradingTime, 25, Some("GBp")))
            )
            source <- buildSource(api)
            events <- source.onSymbol(Some(vodL)).dividends(vod, tradingDay, tradingDay)
          } yield events.map(e => (e.amount, e.currency)) must_==
            List((BigDecimal("0.25"), Some(Currency("GBP"))))
        }

        "without a currency of their own are in the instrument's, so a minor unit is converted" >> {
          for {
            api <- londonApi(dividends = List(YahooDividend(tradingTime, 12.5, None)))
            source <- buildSource(api)
            events <- source.onSymbol(Some(vodL)).dividends(vod, tradingDay, tradingDay)
          } yield events.map(e => (e.amount, e.currency)) must_==
            List((BigDecimal("0.125"), Some(Currency("GBP"))))
        }

        "in a major currency of their own are left alone on a minor-unit instrument" >> {
          for {
            api <- londonApi(dividends = List(YahooDividend(tradingTime, 0.3, Some("USD"))))
            source <- buildSource(api)
            events <- source.onSymbol(Some(vodL)).dividends(vod, tradingDay, tradingDay)
          } yield events.map(e => (e.amount, e.currency)) must_==
            List((BigDecimal("0.3"), Some(Currency("USD"))))
        }

        "keep six decimals after dividing, rounding half up" >> {
          for {
            api <- londonApi(dividends = List(YahooDividend(tradingTime, 1.23445, None)))
            source <- buildSource(api)
            events <- source.onSymbol(Some(vodL)).dividends(vod, tradingDay, tradingDay)
          } yield {
            events.map(_.amount) must_== List(BigDecimal("0.012345"))
            events.map(_.amount.scale) must_== List(6)
          }
        }
      }
    }
  }
}
