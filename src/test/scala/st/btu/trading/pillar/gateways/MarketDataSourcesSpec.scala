package st.btu.trading.pillar.gateways

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.yahoo.*
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.market.YahooSymbol.value
import st.btu.trading.pillar.testkit.MarketDataFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZonedDateTime}

class MarketDataSourcesSpec extends Specification with CatsEffect {

  private val sber = Ticker("SBER")
  private val aapl = Ticker("AAPL")
  private val vod = Ticker("VOD")
  private val cets = ClassCode("CETS")
  private val vodL = YahooSymbol("VOD.L")

  private val tradingDay = LocalDate.of(2024, 1, 10)

  // VOD.L's chart and metadata as Yahoo serves them, for the specs that run the real Yahoo adapter.
  private def vodLApi: IO[MarketDataFakes.FakeYahooApi] =
    MarketDataFakes.yahooApi(
      charts = Map(
        vodL.value -> IO.pure(
          Some(
            YahooChart(
              List(
                YahooQuote(
                  ZonedDateTime.parse("2024-01-10T12:00:00Z"),
                  1,
                  2,
                  0.5,
                  1.5,
                  1.5,
                  100,
                  inProgress = false
                )
              ),
              "GBP"
            )
          )
        )
      ),
      metadata = Map(
        vodL.value -> IO.pure(
          YahooMetadata(
            symbol = vodL.value,
            exchangeName = "LSE",
            instrumentType = "EQUITY",
            currency = "GBP",
            exchangeTimezoneName = "Europe/London",
            regularMarketTime = None,
            regularMarketPrice = None
          )
        )
      )
    )

  private def yahooAdapter(api: YahooApi[IO]): IO[YahooMarketDataSource[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[YahooMarketDataSource[IO]])
    YahooMarketDataSource.create[IO, IO](api)
  }

  "MarketDataSourcesImpl" >> {
    "settingFor" >> {

      "returns the setting stored for the ticker" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(settings =
            List(TickerSource(sber, SourceSetting.Tinkoff(Some(cets))))
          )
          setting <- sources.settingFor(sber)
        } yield setting must_== SourceSetting.Tinkoff(Some(cets))
      }

      "falls back to the default for a ticker with no stored setting" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(
            settings = List(TickerSource(sber, SourceSetting.Tinkoff(None))),
            default = SourceSetting.Tinkoff(Some(cets))
          )
          setting <- sources.settingFor(aapl)
        } yield setting must_== SourceSetting.Tinkoff(Some(cets))
      }
    }

    "forTicker" >> {

      "serves a ticker set to Yahoo with the Yahoo adapter" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource()
          sources <- MarketDataFakes.marketDataSources(
            yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)),
            tinkoff = Some(MarketDataFakes.boardViews),
            settings = List(TickerSource(sber, SourceSetting.Yahoo(None))),
            default = SourceSetting.Tinkoff(None)
          )
          resolved <- sources.forTicker(sber)
        } yield resolved must_== yahoo
      }

      "serves a ticker set to a Yahoo symbol with a view of that symbol" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(
            yahoo = Some(MarketDataFakes.symbolViews),
            settings = List(TickerSource(vod, SourceSetting.Yahoo(Some(vodL))))
          )
          resolved <- sources.forTicker(vod)
        } yield resolved.origin must_== SourceSetting.Yahoo(Some(vodL))
      }

      "serves a ticker set to Yahoo without a symbol with a view of the ticker as given" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(
            yahoo = Some(MarketDataFakes.symbolViews),
            settings = List(TickerSource(vod, SourceSetting.Yahoo(None)))
          )
          resolved <- sources.forTicker(vod)
        } yield resolved.origin must_== SourceSetting.Yahoo(None)
      }

      "serves a ticker set to Tinkoff on the board its setting names" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(
            tinkoff = Some(MarketDataFakes.boardViews),
            settings = List(TickerSource(sber, SourceSetting.Tinkoff(Some(cets))))
          )
          resolved <- sources.forTicker(sber)
        } yield resolved.origin must_== SourceSetting.Tinkoff(Some(cets))
      }

      "serves a ticker set to Tinkoff without a board on the adapter's default board" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(
            tinkoff = Some(MarketDataFakes.boardViews),
            settings = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
          )
          resolved <- sources.forTicker(sber)
        } yield resolved.origin must_== SourceSetting.Tinkoff(None)
      }

      "serves a ticker with no stored setting according to the default" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource()
          onYahoo <- MarketDataFakes.marketDataSources(yahoo =
            Some(MarketDataFakes.yahooSymbolsOver(yahoo))
          )
          onBoard <- MarketDataFakes.marketDataSources(
            tinkoff = Some(MarketDataFakes.boardViews),
            default = SourceSetting.Tinkoff(Some(cets))
          )
          byDefaultYahoo <- onYahoo.forTicker(aapl)
          byDefaultBoard <- onBoard.forTicker(aapl)
        } yield {
          byDefaultYahoo must_== yahoo
          byDefaultBoard.origin must_== SourceSetting.Tinkoff(Some(cets))
        }
      }

      "aliased Yahoo ticker" >> {

        "queries Yahoo for the symbol and reports bars under the ticker" >> {
          for {
            api <- vodLApi
            yahoo <- yahooAdapter(api)
            sources <- MarketDataFakes.marketDataSources(
              yahoo = Some(yahoo),
              settings = List(TickerSource(vod, SourceSetting.Yahoo(Some(vodL))))
            )
            resolved <- sources.forTicker(vod)
            bars <- resolved.dailyBars(vod, tradingDay, tradingDay)
            asked <- api.dailyChartCalls
          } yield {
            asked must_== List(vodL.value)
            bars.map(_.ticker) must_== List(vod)
          }
        }
      }

      "unconfigured adapter" >> {

        "fails naming the source when the ticker is set to Tinkoff and no Tinkoff adapter is wired" >> {
          for {
            yahoo <- MarketDataFakes.marketDataSource()
            sources <- MarketDataFakes.marketDataSources(
              yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)),
              settings = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
            )
            result <- sources.forTicker(sber).attempt
          } yield result must beLeft[Throwable].like {
            case error @ MarketDataError.SourceNotConfigured(source) =>
              source must_== Source.Tinkoff
              error.getMessage must contain(Source.Tinkoff.value)
          }
        }

        "fails naming the source when the default is Yahoo and no Yahoo adapter is wired" >> {
          for {
            sources <- MarketDataFakes.marketDataSources(tinkoff = Some(MarketDataFakes.boardViews))
            result <- sources.forTicker(aapl).attempt
          } yield result must beLeft[Throwable](MarketDataError.SourceNotConfigured(Source.Yahoo))
        }
      }
    }
  }
}
