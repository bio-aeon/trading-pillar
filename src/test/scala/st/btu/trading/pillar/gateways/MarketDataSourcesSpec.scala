package st.btu.trading.pillar.gateways

import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.testkit.MarketDataFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.BoardView

class MarketDataSourcesSpec extends Specification with CatsEffect {

  private val sber = Ticker("SBER")
  private val aapl = Ticker("AAPL")
  private val cets = ClassCode("CETS")

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
          yahoo <- MarketDataFakes.marketDataSource(src = Source.Yahoo)
          sources <- MarketDataFakes.marketDataSources(
            yahoo = Some(yahoo),
            tinkoff = Some(MarketDataFakes.boardViews),
            settings = List(TickerSource(sber, SourceSetting.Yahoo)),
            default = SourceSetting.Tinkoff(None)
          )
          resolved <- sources.forTicker(sber)
        } yield resolved must_== yahoo
      }

      "serves a ticker set to Tinkoff on the board its setting names" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(
            tinkoff = Some(MarketDataFakes.boardViews),
            settings = List(TickerSource(sber, SourceSetting.Tinkoff(Some(cets))))
          )
          resolved <- sources.forTicker(sber)
        } yield resolved must_== BoardView(Some(cets))
      }

      "serves a ticker set to Tinkoff without a board on the adapter's default board" >> {
        for {
          sources <- MarketDataFakes.marketDataSources(
            tinkoff = Some(MarketDataFakes.boardViews),
            settings = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
          )
          resolved <- sources.forTicker(sber)
        } yield resolved must_== BoardView(None)
      }

      "serves a ticker with no stored setting according to the default" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource(src = Source.Yahoo)
          onYahoo <- MarketDataFakes.marketDataSources(yahoo = Some(yahoo))
          onBoard <- MarketDataFakes.marketDataSources(
            tinkoff = Some(MarketDataFakes.boardViews),
            default = SourceSetting.Tinkoff(Some(cets))
          )
          byDefaultYahoo <- onYahoo.forTicker(aapl)
          byDefaultBoard <- onBoard.forTicker(aapl)
        } yield {
          byDefaultYahoo must_== yahoo
          byDefaultBoard must_== BoardView(Some(cets))
        }
      }

      "unconfigured adapter" >> {

        "fails naming the source when the ticker is set to Tinkoff and no Tinkoff adapter is wired" >> {
          for {
            yahoo <- MarketDataFakes.marketDataSource(src = Source.Yahoo)
            sources <- MarketDataFakes.marketDataSources(
              yahoo = Some(yahoo),
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
