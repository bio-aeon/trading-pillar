package st.btu.trading.pillar.gateways

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{MarketDataError, Source, Ticker, WatchlistItem}
import st.btu.trading.pillar.testkit.MarketDataFakes

import java.time.{ZoneOffset, ZonedDateTime}

class MarketDataSourcesSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val ticker = Ticker("SBER")

  private def watchlistItem(source: Source): WatchlistItem =
    WatchlistItem(
      accountId,
      ticker,
      source,
      enabled = true,
      None,
      ZonedDateTime.now(ZoneOffset.UTC)
    )

  "MarketDataSourcesImpl" >> {
    "forTicker" >> {

      "resolves the source of the watchlist item carrying the ticker, when the ticker is on a watchlist" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource(src = Source.Yahoo)
          tinkoff <- MarketDataFakes.marketDataSource(src = Source.Tinkoff)
          watchlist <- MarketDataFakes.watchlistStorage(List(watchlistItem(Source.Tinkoff)))
          sources = new MarketDataSourcesImpl[IO](List(yahoo, tinkoff), watchlist, Source.Yahoo)
          resolved <- sources.forTicker(ticker)
        } yield resolved.source must_== Source.Tinkoff
      }

      "falls back to the default source when the ticker is on no watchlist" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource(src = Source.Yahoo)
          watchlist <- MarketDataFakes.watchlistStorage(List(watchlistItem(Source.Tinkoff)))
          sources = new MarketDataSourcesImpl[IO](List(yahoo), watchlist, Source.Yahoo)
          // A different ticker than the one on the watchlist.
          resolved <- sources.forTicker(Ticker("AAPL"))
        } yield resolved.source must_== Source.Yahoo
      }

      "fails when the resolved source is not configured" >> {
        for {
          watchlist <- MarketDataFakes.watchlistStorage(List(watchlistItem(Source.Tinkoff)))
          sources = new MarketDataSourcesImpl[IO](List.empty, watchlist, Source.Yahoo)
          result <- sources.forTicker(ticker).attempt
        } yield result must beLeft[Throwable].like { case MarketDataError.Other(message, cause) =>
          message must contain(Source.Tinkoff.value)
          cause must beNone
        }
      }
    }

    "forSource" >> {

      "returns the configured source for a known source" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource(src = Source.Yahoo)
          watchlist <- MarketDataFakes.watchlistStorage()
        } yield {
          val sources = new MarketDataSourcesImpl[IO](List(yahoo), watchlist, Source.Yahoo)
          sources.forSource(Source.Yahoo) must beSome(yahoo)
        }
      }

      "returns None for a source with nothing configured" >> {
        for {
          watchlist <- MarketDataFakes.watchlistStorage()
        } yield {
          val sources = new MarketDataSourcesImpl[IO](List.empty, watchlist, Source.Yahoo)
          sources.forSource(Source.Tinkoff) must beNone
        }
      }
    }
  }
}
