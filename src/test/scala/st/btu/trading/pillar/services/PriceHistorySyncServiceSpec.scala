package st.btu.trading.pillar.services

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.{MarketDataSource, MarketDataSourcesImpl}
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.{DividendStorage, InstrumentStorage, PriceHistoryStorage}
import st.btu.trading.pillar.testkit.MarketDataFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class PriceHistorySyncServiceSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val ticker = Ticker("AAPL")
  private val today = LocalDate.of(2024, 6, 15)

  private val config = PriceHistorySyncConfig(
    interval = 1.hour,
    backfillYears = 2,
    overlapDays = 5,
    rateLimitBackoff = 60.seconds,
    metadataRefresh = 24.hours
  )

  private def item: WatchlistItem =
    WatchlistItem(
      accountId,
      ticker,
      Source.Yahoo,
      enabled = true,
      None,
      ZonedDateTime.now(ZoneOffset.UTC)
    )

  private def bar(date: LocalDate): Bar =
    Bar(ticker, date, 1, 1, 1, 1, 1, 100, Source.Yahoo)

  private def buildService(
    source: MarketDataSource[IO],
    priceHistory: PriceHistoryStorage[IO],
    instruments: InstrumentStorage[IO],
    dividends: DividendStorage[IO]
  ): IO[PriceHistorySyncService[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[PriceHistorySyncService[IO]])
    for {
      watchlist <- MarketDataFakes.watchlistStorage()
      sources = new MarketDataSourcesImpl[IO](List(source), watchlist, source.source)
      service <- PriceHistorySyncServiceImpl.create[IO, IO](
        sources,
        priceHistory,
        instruments,
        dividends,
        config
      )
    } yield service
  }

  "PriceHistorySyncServiceImpl" >> {
    "syncSymbol" >> {

      "backfills from today - backfillYears when the symbol has no history" >> {
        for {
          source <- MarketDataFakes.marketDataSource(bars =
            Map(ticker -> IO.pure(List(bar(today))))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
        } yield {
          report.since must_== today.minusYears(config.backfillYears.toLong)
          report.until must_== today
        }
      }

      "syncs since lastBarDate - overlapDays when bars already exist" >> {
        val lastBar = today.minusDays(30)
        for {
          source <- MarketDataFakes.marketDataSource(bars =
            Map(ticker -> IO.pure(List(bar(today))))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage(List(bar(lastBar)))
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
        } yield report.since must_== lastBar.minusDays(config.overlapDays.toLong)
      }

      "skips the fetch when since (lastBarDate - overlapDays) is after the requested until date" >> {
        val lastBar = today
        for {
          source <- MarketDataFakes.marketDataSource(bars =
            Map(ticker -> IO.pure(List(bar(today))))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage(List(bar(lastBar)))
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today.minusDays(10))
          dailyBarsCalls <- source.dailyBarsCalls
        } yield {
          report.fetched must_== 0
          report.upserted must_== 0
          dailyBarsCalls must beEmpty
        }
      }

      "reports the fetched and upserted counts from the source and storage" >> {
        for {
          source <- MarketDataFakes.marketDataSource(bars =
            Map(ticker -> IO.pure(List(bar(today), bar(today.minusDays(1)))))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
          stored <- priceHistory.allBars
        } yield {
          report.fetched must_== 2
          report.upserted must_== 2
          stored must haveSize(2)
        }
      }

      "skips refreshing instrument metadata when it was updated recently" >> {
        val freshInfo = InstrumentInfo(
          ticker,
          None,
          "EQUITY",
          "USD",
          "NMS",
          "America/New_York",
          None,
          ZonedDateTime.now(ZoneOffset.UTC)
        )
        for {
          source <- MarketDataFakes.marketDataSource(
            bars = Map(ticker -> IO.pure(List.empty)),
            info = Map(ticker -> IO.pure(Some(freshInfo)))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage(Map(ticker -> freshInfo))
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          _ <- service.syncSymbol(item, today)
          calls <- source.instrumentInfoCalls
        } yield calls must beEmpty
      }

      "refreshes instrument metadata when missing or stale, without failing the sync" >> {
        val staleInfo = InstrumentInfo(
          ticker,
          None,
          "EQUITY",
          "USD",
          "NMS",
          "America/New_York",
          None,
          ZonedDateTime.now(ZoneOffset.UTC).minusDays(2)
        )
        val freshInfo = staleInfo.copy(updatedAt = ZonedDateTime.now(ZoneOffset.UTC))
        for {
          source <- MarketDataFakes.marketDataSource(
            bars = Map(ticker -> IO.pure(List.empty)),
            info = Map(ticker -> IO.pure(Some(freshInfo)))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage(Map(ticker -> staleInfo))
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          _ <- service.syncSymbol(item, today)
          calls <- source.instrumentInfoCalls
          stored <- instruments.all
        } yield {
          calls must_== List(ticker)
          stored.get(ticker) must beSome(freshInfo)
        }
      }

      "does not fail the sync when refreshing instrument metadata fails" >> {
        for {
          source <- MarketDataFakes.marketDataSource(
            bars = Map(ticker -> IO.pure(List.empty)),
            info = Map(ticker -> IO.raiseError(new RuntimeException("boom")))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
        } yield report.ticker must_== ticker
      }

      "skips with a warning instead of failing when no source is configured for the item's source" >> {
        implicit val logs: Logs[IO, IO] =
          Logs.const[IO, IO](Logging.Make.plain[IO].forService[PriceHistorySyncService[IO]])
        for {
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          watchlist <- MarketDataFakes.watchlistStorage()
          // No MarketDataSource configured at all, so forSource(item.source) is None.
          sources = new MarketDataSourcesImpl[IO](List.empty, watchlist, Source.Yahoo)
          service <- PriceHistorySyncServiceImpl.create[IO, IO](
            sources,
            priceHistory,
            instruments,
            dividends,
            config
          )
          report <- service.syncSymbol(item, today)
        } yield {
          report.fetched must_== 0
          report.upserted must_== 0
          report.dividends must_== 0
        }
      }

      "syncs dividends for the same window as bars and reports the count" >> {
        val div =
          DividendEvent(ticker, today.minusDays(10), BigDecimal("1.50"), Some("USD"), Source.Yahoo)
        for {
          source <- MarketDataFakes.marketDataSource(
            bars = Map(ticker -> IO.pure(List(bar(today)))),
            dividendEvents = Map(ticker -> IO.pure(List(div)))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
          stored <- dividends.all
        } yield {
          report.dividends must_== 1
          stored must_== List(div)
        }
      }

      "does not fail the sync when fetching dividends fails, reporting zero dividends synced" >> {
        for {
          source <- MarketDataFakes.marketDataSource(
            bars = Map(ticker -> IO.pure(List(bar(today)))),
            dividendEvents = Map(ticker -> IO.raiseError(new RuntimeException("boom")))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
          storedBars <- priceHistory.allBars
        } yield {
          report.dividends must_== 0
          report.upserted must_== 1
          storedBars must haveSize(1)
        }
      }
    }
  }
}
