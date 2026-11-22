package st.btu.trading.pillar.services

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{Clock, IO, Ref}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.{InstrumentDirectory, MarketDataSources}
import st.btu.trading.pillar.models.Account
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.{AccountStorage, TickerSourceStorage, WatchlistStorage}
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.MarketDataFakes.{
  FakeTickerSourceStorage,
  FakeWatchlistStorage,
  brokerInstrument,
  delayForIO
}
import st.btu.trading.pillar.testkit.*
import tofu.logging.{Logging, Logs}

import java.time.{Instant, ZoneOffset, ZonedDateTime}

class HeldInstrumentsServiceImplSpec extends Specification with CatsEffect {

  private val created = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
  private val tinkoff = Account(AccountId(1), "tinkoff", Broker.Tinkoff, created)
  private val ibkr = Account(AccountId(2), "ibkr", Broker.Ibkr, created)
  private val binance = Account(AccountId(3), "binance", Broker.Binance, created)

  private val now = Instant.parse("2026-11-10T12:00:00Z")
  private val earlier = Instant.parse("2026-10-01T10:00:00Z")

  private val note = "Added because the account holds it"

  private val sber = brokerInstrument("F1", Ticker("SBER"))
  private val gazprom = brokerInstrument("F2", Ticker("GAZP"))
  private val vodafone = ibkrInstrument("12345", "VOD", "LSE")
  private val bitcoin = binanceInstrument("BTC", "BTCUSDC")

  private def ibkrInstrument(conid: String, symbol: String, exchange: String): BrokerInstrument =
    BrokerInstrument(
      IbkrFakes.instrumentKey(conid),
      Ticker(symbol),
      Some(ClassCode(exchange)),
      symbol,
      "STK",
      Currency("GBP"),
      created
    )

  private def binanceInstrument(asset: String, ticker: String): BrokerInstrument =
    BrokerInstrument(
      InstrumentKey(Broker.Binance, BrokerInstrumentId(asset)),
      Ticker(ticker),
      None,
      asset,
      "crypto",
      Currency("USDC"),
      created
    )

  // The same at Binance, for the coin with this asset code.
  private def movedAtBinance(
    owner: Account,
    asset: String,
    quantity: BigDecimal,
    currency: String = "USDC"
  ): AccountOperation =
    accountOperation(
      owner.id,
      OperationKind.Buy,
      0,
      earlier,
      quantity = quantity,
      currency = currency
    )
      .copy(instrument = Some(InstrumentKey(Broker.Binance, BrokerInstrumentId(asset))))

  // `quantity` units of the Tinkoff instrument with this FIGI, bought, or sold when negative.
  private def moved(owner: Account, figi: String, quantity: BigDecimal): AccountOperation = {
    val kind = if (quantity.signum > 0) OperationKind.Buy else OperationKind.Sell
    accountOperation(owner.id, kind, 0, earlier, Some(figi), quantity)
  }

  // The same at Interactive Brokers, for the contract with this id.
  private def movedAtIb(owner: Account, conid: String, quantity: BigDecimal): AccountOperation =
    accountOperation(owner.id, OperationKind.Buy, 0, earlier, quantity = quantity)
      .copy(instrument = Some(IbkrFakes.instrumentKey(conid)))

  // Cash of `currency` paid into the account, which `state` says how far has got.
  private def paidIn(
    owner: Account,
    currency: String,
    state: OperationState = OperationState.Executed
  ): AccountOperation =
    accountOperation(
      owner.id,
      OperationKind.Deposit,
      100,
      earlier,
      currency = currency,
      state = state
    )

  private def entry(
    owner: Account,
    ticker: String,
    enabled: Boolean = true,
    note: Option[String] = None
  ): WatchlistItem =
    WatchlistItem(owner.id, Ticker(ticker), enabled, note, created)

  private def held(owner: Account, ticker: String): WatchlistItem =
    WatchlistItem(owner.id, Ticker(ticker), enabled = true, Some(note), nowUtc)

  // The entry that the service adds for the rate ticker of a currency.
  private def rate(owner: Account, ticker: String, currency: String): WatchlistItem =
    WatchlistItem(
      owner.id,
      Ticker(ticker),
      enabled = true,
      Some(s"Added for the exchange rate of $currency"),
      nowUtc
    )

  private def nowUtc: ZonedDateTime = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)

  private def directoryOf(
    known: List[BrokerInstrument],
    failing: Set[InstrumentKey] = Set.empty
  ): InstrumentDirectory[IO] =
    new InstrumentDirectory[IO] {
      def byKey(key: InstrumentKey): IO[Option[BrokerInstrument]] =
        if (failing(key)) IO.raiseError(new RuntimeException("directory unavailable"))
        else IO.pure(known.find(_.instrument == key))
    }

  // A router that has an adapter for both sources a held ticker can be priced from.
  private val servingBoth: IO[MarketDataSources[IO]] =
    MarketDataFakes.marketDataSources(
      yahoo = Some(MarketDataFakes.symbolViews),
      tinkoff = Some(MarketDataFakes.boardViews)
    )

  private val plainLogging: Logging[IO] =
    Logging.Make.plain[IO].forService[HeldInstrumentsService[IO]]

  // No base currency, so that no rate ticker is wanted.
  private val noRates = RateTickerConfig(Set.empty)

  private def serviceOver(
    accounts: AccountStorage[IO],
    operations: List[AccountOperation],
    directory: InstrumentDirectory[IO],
    watchlist: WatchlistStorage[IO],
    settings: TickerSourceStorage[IO],
    sources: MarketDataSources[IO],
    logging: Logging[IO] = plainLogging,
    rates: RateTickerConfig = noRates
  ): IO[HeldInstrumentsService[IO]] = {
    implicit val clock: Clock[IO] = McpFakes.clockAt(now)
    implicit val logs: Logs[IO, IO] = Logs.const[IO, IO](logging)
    for {
      operationStorage <- AnalyticsFakes.accountOperationStorage(operations)
      service <- HeldInstrumentsServiceImpl.create[IO, IO](
        accounts,
        operationStorage,
        watchlist,
        settings,
        directory,
        sources,
        rates
      )
    } yield service
  }

  private final case class Fixture(
    watchlist: FakeWatchlistStorage,
    settings: FakeTickerSourceStorage,
    service: HeldInstrumentsService[IO]
  )

  private def fixture(
    accounts: List[Account] = List(tinkoff),
    operations: List[AccountOperation] = Nil,
    directory: InstrumentDirectory[IO] = directoryOf(List(sber, gazprom, vodafone)),
    watchlist: List[WatchlistItem] = Nil,
    settings: List[TickerSource] = Nil,
    router: IO[MarketDataSources[IO]] = servingBoth,
    logging: Logging[IO] = plainLogging,
    rates: RateTickerConfig = noRates
  ): IO[Fixture] =
    for {
      accountStorage <- AnalyticsFakes.accountStorage(accounts)
      watchlistStorage <- MarketDataFakes.watchlistStorage(watchlist)
      settingsStorage <- MarketDataFakes.tickerSourceStorage(settings)
      sources <- router
      service <- serviceOver(
        accountStorage,
        operations,
        directory,
        watchlistStorage,
        settingsStorage,
        sources,
        logging,
        rates
      )
    } yield Fixture(watchlistStorage, settingsStorage, service)

  "HeldInstrumentsServiceImpl" >> {
    "addToWatchlists" >> {
      "held instruments" >> {
        "are added to the watchlist enabled, with a note saying the account holds them" >> {
          for {
            f <- fixture(operations = List(moved(tinkoff, "F1", 10)))
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== List(held(tinkoff, "SBER"))
        }

        "are added to the watchlist of the account that holds them" >> {
          val operations = List(moved(tinkoff, "F1", 10), movedAtIb(ibkr, "12345", 5))
          for {
            f <- fixture(List(tinkoff, ibkr), operations)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must contain(exactly(held(tinkoff, "SBER"), held(ibkr, "VOD")))
        }

        "are added once when an account holds two instruments of one ticker" >> {
          val onAnotherBoard = brokerInstrument("F3", Ticker("SBER"), classCode = "TQTF")
          val operations = List(moved(tinkoff, "F1", 10), moved(tinkoff, "F3", 4))
          for {
            f <- fixture(
              operations = operations,
              directory = directoryOf(List(sber, onAnotherBoard))
            )
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== List(held(tinkoff, "SBER"))
        }

        "do not include a position that was sold" >> {
          val operations = List(moved(tinkoff, "F1", 10), moved(tinkoff, "F1", -10))
          for {
            f <- fixture(operations = operations)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must beEmpty
        }

        "do not include an instrument the directory does not know" >> {
          val operations = List(moved(tinkoff, "UNKNOWN", 10), moved(tinkoff, "F1", 10))
          for {
            f <- fixture(operations = operations)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== List(held(tinkoff, "SBER"))
        }

        "do not include an instrument whose lookup fails, which is logged, and the others are added" >> {
          val operations = List(moved(tinkoff, "F1", 10), moved(tinkoff, "F2", 10))
          for {
            logging <- LogFakes.recordingLogging
            f <- fixture(
              operations = operations,
              directory = directoryOf(List(sber, gazprom), failing = Set(sber.instrument)),
              logging = logging
            )
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
            entries <- logging.entries
          } yield {
            items must_== List(held(tinkoff, "GAZP"))
            entries.exists(_.contains("F1")) must beTrue
          }
        }
      }

      "entries already on the watchlist" >> {
        "are left as they are, a disabled one included" >> {
          val existing = List(
            entry(tinkoff, "SBER", enabled = false, note = Some("paused")),
            entry(tinkoff, "GAZP", note = Some("mine"))
          )
          val operations = List(moved(tinkoff, "F1", 10), moved(tinkoff, "F2", 10))
          for {
            f <- fixture(operations = operations, watchlist = existing)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== existing
        }

        "stay when the account no longer holds the instrument" >> {
          val existing = List(entry(tinkoff, "SBER"))
          val operations = List(moved(tinkoff, "F1", 10), moved(tinkoff, "F1", -10))
          for {
            f <- fixture(operations = operations, watchlist = existing)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== existing
        }
      }

      "source settings" >> {
        "are written for held tickers that have none, by the pricing rule of their broker" >> {
          val operations = List(moved(tinkoff, "F1", 10), movedAtIb(ibkr, "12345", 5))
          for {
            f <- fixture(List(tinkoff, ibkr), operations)
            _ <- f.service.addToWatchlists
            settings <- f.settings.all
          } yield settings must contain(
            exactly(
              TickerSource(Ticker("SBER"), SourceSetting.Tinkoff(Some(ClassCode("TQBR")))),
              TickerSource(Ticker("VOD"), SourceSetting.Yahoo(Some(YahooSymbol("VOD.L"))))
            )
          )
        }

        "are not written for a source the router does not serve, and the ticker still joins the watchlist" >> {
          val operations = List(moved(tinkoff, "F1", 10), movedAtIb(ibkr, "12345", 5))
          for {
            f <- fixture(
              List(tinkoff, ibkr),
              operations,
              router = MarketDataFakes.marketDataSources(yahoo = Some(MarketDataFakes.symbolViews))
            )
            _ <- f.service.addToWatchlists
            settings <- f.settings.all
            items <- f.watchlist.items
          } yield {
            settings must_==
              List(TickerSource(Ticker("VOD"), SourceSetting.Yahoo(Some(YahooSymbol("VOD.L")))))
            items must contain(exactly(held(tinkoff, "SBER"), held(ibkr, "VOD")))
          }
        }

        "are never changed once stored" >> {
          val stored = TickerSource(Ticker("SBER"), SourceSetting.Yahoo(None))
          for {
            f <- fixture(operations = List(moved(tinkoff, "F1", 10)), settings = List(stored))
            _ <- f.service.addToWatchlists
            settings <- f.settings.all
          } yield settings must_== List(stored)
        }

        "are written for a held ticker that is on the watchlist already" >> {
          for {
            f <- fixture(
              operations = List(moved(tinkoff, "F1", 10)),
              watchlist = List(entry(tinkoff, "SBER"))
            )
            _ <- f.service.addToWatchlists
            settings <- f.settings.all
          } yield settings must_==
            List(TickerSource(Ticker("SBER"), SourceSetting.Tinkoff(Some(ClassCode("TQBR")))))
        }

        "are written before the ticker is added to the watchlist" >> {
          for {
            events <- Ref.of[IO, List[String]](Nil)
            accounts <- AnalyticsFakes.accountStorage(List(tinkoff))
            watchlist <- MarketDataFakes.watchlistStorage()
            settings <- MarketDataFakes.tickerSourceStorage()
            sources <- servingBoth
            recordingWatchlist = new WatchlistStorage[IO] {
              def allEnabled: IO[List[WatchlistItem]] = watchlist.allEnabled
              def byAccount(accountId: AccountId): IO[List[WatchlistItem]] =
                watchlist.byAccount(accountId)
              def add(item: WatchlistItem): IO[Unit] =
                events.update(_ :+ "watchlist") >> watchlist.add(item)
              def remove(accountId: AccountId, ticker: Ticker): IO[Boolean] =
                watchlist.remove(accountId, ticker)
            }
            recordingSettings = new TickerSourceStorage[IO] {
              def find(ticker: Ticker): IO[Option[TickerSource]] = settings.find(ticker)
              def all: IO[List[TickerSource]] = settings.all
              def upsert(entry: TickerSource): IO[Unit] =
                events.update(_ :+ "setting") >> settings.upsert(entry)
              def delete(ticker: Ticker): IO[Boolean] = settings.delete(ticker)
            }
            service <- serviceOver(
              accounts,
              List(moved(tinkoff, "F1", 10)),
              directoryOf(List(sber)),
              recordingWatchlist,
              recordingSettings,
              sources
            )
            _ <- service.addToWatchlists
            recorded <- events.get
          } yield recorded must_== List("setting", "watchlist")
        }

        "are not written for an exchange without a rule, which is logged with the ticker" >> {
          val onSmart = ibkrInstrument("999", "AAPL", "SMART")
          for {
            logging <- LogFakes.recordingLogging
            f <- fixture(
              List(ibkr),
              List(movedAtIb(ibkr, "999", 5)),
              directoryOf(List(onSmart)),
              logging = logging
            )
            _ <- f.service.addToWatchlists
            settings <- f.settings.all
            items <- f.watchlist.items
            entries <- logging.entries
          } yield {
            settings must beEmpty
            items must_== List(held(ibkr, "AAPL"))
            entries.exists(entry => entry.contains("AAPL") && entry.contains("SMART")) must beTrue
          }
        }
      }

      "Binance coins" >> {
        "are set to Binance and put on the watchlist while the router serves Binance" >> {
          for {
            binanceSource <- MarketDataFakes.marketDataSource(SourceSetting.Binance)
            f <- fixture(
              List(binance),
              List(movedAtBinance(binance, "BTC", 2)),
              directoryOf(List(bitcoin)),
              router = MarketDataFakes.marketDataSources(binance = Some(binanceSource))
            )
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
            settings <- f.settings.all
          } yield {
            items must_== List(held(binance, "BTCUSDC"))
            settings must_== List(TickerSource(Ticker("BTCUSDC"), SourceSetting.Binance))
          }
        }

        "are left off the watchlist and given no source setting while the router does not serve Binance, which is logged with the ticker" >> {
          for {
            logging <- LogFakes.recordingLogging
            f <- fixture(
              List(binance),
              List(movedAtBinance(binance, "BTC", 2)),
              directoryOf(List(bitcoin)),
              logging = logging
            )
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
            settings <- f.settings.all
            entries <- logging.entries
          } yield {
            items must beEmpty
            settings must beEmpty
            entries.exists(_.contains("BTCUSDC")) must beTrue
          }
        }

        "with a source setting of their own are watched all the same, the setting left as it is" >> {
          val chosen =
            TickerSource(Ticker("BTCUSDC"), SourceSetting.Yahoo(Some(YahooSymbol("BTC-USD"))))
          for {
            f <- fixture(
              List(binance),
              List(movedAtBinance(binance, "BTC", 2)),
              directoryOf(List(bitcoin)),
              settings = List(chosen)
            )
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
            settings <- f.settings.all
          } yield {
            items must_== List(held(binance, "BTCUSDC"))
            settings must_== List(chosen)
          }
        }

        "do not keep another account's instruments off its watchlist" >> {
          val operations = List(moved(tinkoff, "F1", 10), movedAtBinance(binance, "BTC", 2))
          for {
            f <- fixture(List(tinkoff, binance), operations, directoryOf(List(sber, bitcoin)))
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== List(held(tinkoff, "SBER"))
        }
      }

      "rate tickers" >> {
        val dollars = RateTickerConfig(Set(Currency("USD")))

        "are added for each currency the executed operations are in, enabled, with a note naming it" >> {
          val operations = List(paidIn(tinkoff, "EUR"), paidIn(tinkoff, "USDT"))
          for {
            f <- fixture(operations = operations, rates = dollars)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must contain(
            exactly(rate(tinkoff, "EURUSD=X", "EUR"), rate(tinkoff, "USDTUSD=X", "USDT"))
          )
        }

        "are added once for a currency, however many operations are in it" >> {
          val operations = List(paidIn(tinkoff, "EUR"), paidIn(tinkoff, "EUR"))
          for {
            f <- fixture(operations = operations, rates = dollars)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== List(rate(tinkoff, "EURUSD=X", "EUR"))
        }

        "are added for the currency a held instrument is priced in, though the operations are in the base currency" >> {
          for {
            binanceSource <- MarketDataFakes.marketDataSource(SourceSetting.Binance)
            f <- fixture(
              List(binance),
              List(movedAtBinance(binance, "BTC", 2, currency = "USD")),
              directoryOf(List(bitcoin)),
              router = MarketDataFakes.marketDataSources(binance = Some(binanceSource)),
              rates = dollars
            )
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must contain(
            exactly(held(binance, "BTCUSDC"), rate(binance, "USDCUSD=X", "USDC"))
          )
        }

        "are not added for a base currency" >> {
          for {
            f <- fixture(operations = List(paidIn(tinkoff, "USD")), rates = dollars)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must beEmpty
        }

        "are not added for the currency of an operation that is not executed" >> {
          val operations = List(
            paidIn(tinkoff, "EUR", OperationState.Pending),
            paidIn(tinkoff, "GBP", OperationState.Canceled)
          )
          for {
            f <- fixture(operations = operations, rates = dollars)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must beEmpty
        }

        "are not added when no base currency is given" >> {
          for {
            f <- fixture(operations = List(paidIn(tinkoff, "EUR")))
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must beEmpty
        }

        "are the ones the configuration names for a currency whose rate is not the conventional pair" >> {
          val named =
            RateTickerConfig(Set(Currency("USD")), Map(Currency("USDT") -> Ticker("USDT-USD")))
          for {
            f <- fixture(operations = List(paidIn(tinkoff, "USDT")), rates = named)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== List(rate(tinkoff, "USDT-USD", "USDT"))
        }

        "are added into each base currency that the currency is not" >> {
          val both = RateTickerConfig(Set(Currency("USD"), Currency("EUR")))
          val operations = List(paidIn(tinkoff, "USD"), paidIn(tinkoff, "EUR"))
          for {
            f <- fixture(operations = operations, rates = both)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must contain(
            exactly(rate(tinkoff, "EURUSD=X", "EUR"), rate(tinkoff, "USDEUR=X", "USD"))
          )
        }

        "leave an entry already on the watchlist as it is, a disabled one included" >> {
          val existing =
            List(entry(tinkoff, "EURUSD=X", enabled = false, note = Some("paused")))
          for {
            f <- fixture(
              operations = List(paidIn(tinkoff, "EUR")),
              watchlist = existing,
              rates = dollars
            )
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must_== existing
        }

        "are added to the watchlist of the account whose operations are in the currency" >> {
          val operations = List(paidIn(tinkoff, "EUR"), paidIn(ibkr, "GBP"))
          for {
            f <- fixture(List(tinkoff, ibkr), operations, rates = dollars)
            _ <- f.service.addToWatchlists
            items <- f.watchlist.items
          } yield items must contain(
            exactly(rate(tinkoff, "EURUSD=X", "EUR"), rate(ibkr, "GBPUSD=X", "GBP"))
          )
        }

        "are given no source setting" >> {
          for {
            f <- fixture(operations = List(paidIn(tinkoff, "EUR")), rates = dollars)
            _ <- f.service.addToWatchlists
            settings <- f.settings.all
          } yield settings must beEmpty
        }

        "are logged with the ticker and the currency" >> {
          for {
            logging <- LogFakes.recordingLogging
            f <- fixture(
              operations = List(paidIn(tinkoff, "EUR")),
              rates = dollars,
              logging = logging
            )
            _ <- f.service.addToWatchlists
            entries <- logging.entries
          } yield entries.exists(entry => entry.contains("EURUSD=X") && entry.contains("EUR")) must
            beTrue
        }
      }

      "a failing storage" >> {
        "fails the step" >> {
          val unreadable = new RuntimeException("accounts unreadable")
          for {
            accounts <- AnalyticsFakes.accountStorageF(IO.raiseError(unreadable))
            watchlist <- MarketDataFakes.watchlistStorage()
            settings <- MarketDataFakes.tickerSourceStorage()
            sources <- servingBoth
            service <- serviceOver(accounts, Nil, directoryOf(Nil), watchlist, settings, sources)
            outcome <- service.addToWatchlists.attempt
          } yield outcome must beLeft[Throwable](unreadable)
        }
      }
    }
  }
}
