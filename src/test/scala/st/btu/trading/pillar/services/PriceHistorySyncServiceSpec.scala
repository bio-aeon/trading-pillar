package st.btu.trading.pillar.services

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.{MarketDataSource, MarketDataSources, MarketDataSourcesImpl}
import st.btu.trading.pillar.models.domain.{AccountId, Currency}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.{DividendStorage, InstrumentStorage, PriceHistoryStorage}
import st.btu.trading.pillar.testkit.MarketDataFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

private final case class RoutedToTinkoff(
  service: PriceHistorySyncService[IO],
  yahoo: MarketDataFakes.FakeMarketDataSource,
  tinkoff: MarketDataFakes.FakeMarketDataSource,
  priceHistory: MarketDataFakes.FakePriceHistoryStorage,
  instruments: MarketDataFakes.FakeInstrumentStorage
)

class PriceHistorySyncServiceSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[PriceHistorySyncService[IO]])

  private val accountId = AccountId(1)
  private val ticker = Ticker("AAPL")
  private val today = LocalDate.of(2024, 6, 15)

  // Hand-worked from today, backfillYears = 2 and overlapDays = 5: the whole window starts two
  // years back, and a last stored bar of 2024-05-16 resumes five days before it.
  private val windowStart = LocalDate.of(2022, 6, 15)
  private val lastStoredBar = LocalDate.of(2024, 5, 16)
  private val incrementalStart = LocalDate.of(2024, 5, 11)

  private val tqbr = ClassCode("TQBR")
  private val tqtf = ClassCode("TQTF")

  private val usd = Currency("USD")
  private val rub = Currency("RUB")

  private val config = PriceHistorySyncConfig(
    interval = 1.hour,
    backfillYears = 2,
    overlapDays = 5,
    rateLimitBackoff = 60.seconds,
    metadataRefresh = 24.hours
  )

  private def item: WatchlistItem =
    WatchlistItem(accountId, ticker, enabled = true, None, ZonedDateTime.now(ZoneOffset.UTC))

  private def bar(date: LocalDate, source: Source = Source.Yahoo): Bar =
    Bar(ticker, date, 1, 1, 1, 1, 1, 100, source)

  // AAPL's metadata as `source` describes it on `board`; the currency tells the sources apart.
  private def metadata(
    source: Source,
    currency: Currency,
    updatedAt: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
    board: Option[ClassCode] = None
  ): InstrumentInfo =
    InstrumentInfo(
      ticker,
      source,
      board,
      None,
      "EQUITY",
      currency,
      "NMS",
      "America/New_York",
      None,
      updatedAt
    )

  private def serviceOver(
    sources: MarketDataSources[IO],
    priceHistory: PriceHistoryStorage[IO],
    instruments: InstrumentStorage[IO],
    dividends: DividendStorage[IO]
  ): IO[PriceHistorySyncService[IO]] =
    PriceHistorySyncServiceImpl.create[IO, IO](
      sources,
      priceHistory,
      instruments,
      dividends,
      config
    )

  private def buildService(
    source: MarketDataSource[IO],
    priceHistory: PriceHistoryStorage[IO],
    instruments: InstrumentStorage[IO],
    dividends: DividendStorage[IO]
  ): IO[PriceHistorySyncService[IO]] =
    MarketDataFakes
      .marketDataSources(yahoo = Some(source))
      .flatMap(serviceOver(_, priceHistory, instruments, dividends))

  // Yahoo is the default source but AAPL is set to Tinkoff, so only the Tinkoff adapter may be
  // asked.
  private def routedToTinkoff(
    storedBars: List[Bar] = Nil,
    storedMetadata: Map[Ticker, InstrumentInfo] = Map.empty,
    tinkoffMetadata: Option[InstrumentInfo] = None,
    tinkoffBoard: Option[ClassCode] = None
  ): IO[RoutedToTinkoff] =
    for {
      yahoo <- MarketDataFakes.marketDataSource(src = Source.Yahoo)
      tinkoff <- MarketDataFakes.marketDataSource(
        src = Source.Tinkoff,
        bars = Map(ticker -> IO.pure(List(bar(today, Source.Tinkoff)))),
        info = Map(ticker -> IO.pure(tinkoffMetadata)),
        onBoard = tinkoffBoard
      )
      priceHistory <- MarketDataFakes.priceHistoryStorage(storedBars)
      instruments <- MarketDataFakes.instrumentStorage(storedMetadata)
      dividends <- MarketDataFakes.dividendStorage()
      sources <- MarketDataFakes.marketDataSources(
        yahoo = Some(yahoo),
        tinkoff = Some(MarketDataFakes.tinkoffBoardsOver(tinkoff)),
        settings = List(TickerSource(ticker, SourceSetting.Tinkoff(None)))
      )
      service <- serviceOver(sources, priceHistory, instruments, dividends)
    } yield RoutedToTinkoff(service, yahoo, tinkoff, priceHistory, instruments)

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
        for {
          source <- MarketDataFakes.marketDataSource(bars =
            Map(ticker -> IO.pure(List(bar(today))))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage(List(bar(lastStoredBar)))
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
        } yield report.since must_== incrementalStart
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

      "leaves metadata updated recently from the same source alone" >> {
        val freshInfo = metadata(Source.Yahoo, usd)
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
        val staleInfo =
          metadata(Source.Yahoo, usd, ZonedDateTime.now(ZoneOffset.UTC).minusDays(2))
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

      "metadata read from another source" >> {

        "refreshes it although it was updated recently, when the ticker moved to Yahoo" >> {
          val tinkoffInfo = metadata(Source.Tinkoff, rub)
          val yahooInfo = metadata(Source.Yahoo, usd)
          for {
            source <- MarketDataFakes.marketDataSource(
              bars = Map(ticker -> IO.pure(List.empty)),
              info = Map(ticker -> IO.pure(Some(yahooInfo)))
            )
            priceHistory <- MarketDataFakes.priceHistoryStorage()
            instruments <- MarketDataFakes.instrumentStorage(Map(ticker -> tinkoffInfo))
            dividends <- MarketDataFakes.dividendStorage()
            service <- buildService(source, priceHistory, instruments, dividends)
            _ <- service.syncSymbol(item, today)
            stored <- instruments.all
          } yield stored.get(ticker) must beSome(yahooInfo)
        }

        "refreshes it although it was updated recently, when the ticker moved to Tinkoff" >> {
          val yahooInfo = metadata(Source.Yahoo, usd)
          val tinkoffInfo = metadata(Source.Tinkoff, rub)
          for {
            routed <- routedToTinkoff(
              storedMetadata = Map(ticker -> yahooInfo),
              tinkoffMetadata = Some(tinkoffInfo)
            )
            _ <- routed.service.syncSymbol(item, today)
            stored <- routed.instruments.all
          } yield stored.get(ticker) must beSome(tinkoffInfo)
        }
      }

      "origin of the stored data" >> {

        "changed" >> {

          "refetches the whole window and refreshes the metadata when the board changed" >> {
            val storedInfo = metadata(Source.Tinkoff, rub, board = Some(tqbr))
            val refreshedInfo = metadata(Source.Tinkoff, rub, board = Some(tqtf))
            for {
              routed <- routedToTinkoff(
                storedBars = List(bar(lastStoredBar, Source.Tinkoff)),
                storedMetadata = Map(ticker -> storedInfo),
                tinkoffMetadata = Some(refreshedInfo),
                tinkoffBoard = Some(tqtf)
              )
              report <- routed.service.syncSymbol(item, today)
              stored <- routed.instruments.all
            } yield {
              report.since must_== windowStart
              stored.get(ticker) must beSome(refreshedInfo)
            }
          }

          "refetches the whole window when the ticker moved from Yahoo to Tinkoff" >> {
            for {
              routed <- routedToTinkoff(
                storedBars = List(bar(lastStoredBar, Source.Tinkoff)),
                storedMetadata = Map(ticker -> metadata(Source.Yahoo, usd)),
                tinkoffBoard = Some(tqbr)
              )
              report <- routed.service.syncSymbol(item, today)
            } yield report.since must_== windowStart
          }

          "refetches the whole window when the ticker moved from Tinkoff to Yahoo" >> {
            val storedInfo = metadata(Source.Tinkoff, rub, board = Some(tqbr))
            for {
              source <- MarketDataFakes.marketDataSource(bars =
                Map(ticker -> IO.pure(List(bar(today))))
              )
              priceHistory <- MarketDataFakes.priceHistoryStorage(List(bar(lastStoredBar)))
              instruments <- MarketDataFakes.instrumentStorage(Map(ticker -> storedInfo))
              dividends <- MarketDataFakes.dividendStorage()
              service <- buildService(source, priceHistory, instruments, dividends)
              report <- service.syncSymbol(item, today)
            } yield report.since must_== windowStart
          }
        }

        "unchanged" >> {

          "syncs incrementally when the metadata has the same source and board" >> {
            val storedInfo = metadata(Source.Tinkoff, rub, board = Some(tqbr))
            for {
              routed <- routedToTinkoff(
                storedBars = List(bar(lastStoredBar, Source.Tinkoff)),
                storedMetadata = Map(ticker -> storedInfo),
                tinkoffBoard = Some(tqbr)
              )
              report <- routed.service.syncSymbol(item, today)
            } yield report.since must_== incrementalStart
          }
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

      "treats unreadable stored metadata as absent, without failing the sync" >> {
        for {
          source <- MarketDataFakes.marketDataSource(bars =
            Map(ticker -> IO.pure(List(bar(today))))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage(List(bar(lastStoredBar)))
          instruments <- MarketDataFakes.failingInstrumentStorage(new RuntimeException("boom"))
          dividends <- MarketDataFakes.dividendStorage()
          service <- buildService(source, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
        } yield report.since must_== incrementalStart
      }

      "skips with a warning instead of failing when the ticker's source has no adapter configured" >> {
        for {
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          yahoo <- MarketDataFakes.marketDataSource()
          // Only a Yahoo adapter is wired, but the ticker is set to Tinkoff.
          sources <- MarketDataFakes.marketDataSources(
            yahoo = Some(yahoo),
            settings = List(TickerSource(ticker, SourceSetting.Tinkoff(None)))
          )
          service <- serviceOver(sources, priceHistory, instruments, dividends)
          report <- service.syncSymbol(item, today)
          yahooCalls <- yahoo.dailyBarsCalls
        } yield {
          report.source must_== Source.Tinkoff
          report.fetched must_== 0
          report.upserted must_== 0
          report.dividends must_== 0
          yahooCalls must beEmpty
        }
      }

      "fails when the ticker's source setting cannot be read" >> {
        val lookupFailure = MarketDataError.Other("lookup failed", None)
        for {
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          dividends <- MarketDataFakes.dividendStorage()
          yahoo <- MarketDataFakes.marketDataSource()
          brokenSettings <- MarketDataFakes.failingTickerSourceStorage(lookupFailure)
          sources = new MarketDataSourcesImpl[IO](
            Some(yahoo),
            None,
            brokenSettings,
            SourceSetting.Yahoo
          )
          service <- serviceOver(sources, priceHistory, instruments, dividends)
          result <- service.syncSymbol(item, today).attempt
        } yield result must beLeft[Throwable](lookupFailure)
      }

      "routing" >> {

        "fetches from the adapter the ticker is set to and reports that source" >> {
          for {
            routed <- routedToTinkoff()
            report <- routed.service.syncSymbol(item, today)
            yahooCalls <- routed.yahoo.dailyBarsCalls
            tinkoffCalls <- routed.tinkoff.dailyBarsCalls
            stored <- routed.priceHistory.allBars
          } yield {
            report.source must_== Source.Tinkoff
            tinkoffCalls must_== List(ticker)
            yahooCalls must beEmpty
            stored.map(_.source) must_== List(Source.Tinkoff)
          }
        }

        "resumes from the last bar stored for that source, ignoring bars of the other source" >> {
          val yahooLast = today.minusDays(1)
          for {
            routed <- routedToTinkoff(storedBars =
              List(bar(lastStoredBar, Source.Tinkoff), bar(yahooLast, Source.Yahoo))
            )
            report <- routed.service.syncSymbol(item, today)
          } yield report.since must_== incrementalStart
        }
      }

      "syncs dividends for the same window as bars and reports the count" >> {
        val div =
          DividendEvent(ticker, today.minusDays(10), BigDecimal("1.50"), Some(usd), Source.Yahoo)
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
