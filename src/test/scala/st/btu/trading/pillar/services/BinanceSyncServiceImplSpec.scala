package st.btu.trading.pillar.services

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{Clock, IO}
import cats.syntax.flatMap.*
import io.circe.JsonObject
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.{BrokerInstrument, Ticker}
import st.btu.trading.pillar.storages.BrokerInstrumentStorage
import st.btu.trading.pillar.analytics.portfolio.Holdings
import st.btu.trading.pillar.testkit.AlertFakes.{FakeSystemAlerts, RaisedSystemAlert}
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.BinanceFakes.*
import st.btu.trading.pillar.testkit.MarketDataFakes.{FakeBrokerInstrumentStorage, delayForIO}
import st.btu.trading.pillar.testkit.{AlertFakes, BinanceFakes, MarketDataFakes}
import tofu.logging.{Logging, Logs}

import java.time.{Duration, Instant, LocalDate}

class BinanceSyncServiceImplSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[BinanceSyncService[IO]])

  private val accountId = AccountId(1)
  private val account = BinanceAccount(accountId, ApiKey("api-key"), SecretKey("secret-key"))

  // 12:00 on 10 March 2026.
  private val now = at("2026-03-10T12:00:00Z")

  // Reads from the start of October 2025, which is 160 days before.
  private val config = BinanceSyncConfig(historyStart = LocalDate.of(2025, 10, 1))

  private val listed = List(
    pairOf("BTC", "USDT"),
    pairOf("BTC", "USDC"),
    pairOf("ETH", "BTC"),
    pairOf("ETH", "USDT"),
    pairOf("BNB", "USDT"),
    pairOf("BNB", "USDC"),
    pairOf("DOGE", "USDT"),
    pairOf("DOGE", "BTC"),
    pairOf("USDC", "USDT")
  )

  // The sources that are read in windows of 89 days.
  private val longWindowSources = List(
    BinanceSource.Deposit,
    BinanceSource.Withdrawal,
    BinanceSource.FiatDeposit,
    BinanceSource.FiatWithdrawal,
    BinanceSource.FiatBuy,
    BinanceSource.FiatSell
  )

  // Buys of one unit of the base asset of the pair for one unit of its quote, made on 9 March, with
  // the ids given.
  private def tradesOf(pairName: String, ids: Range): List[JsonObject] =
    ids.toList.map(id => trade(pairName, id.toLong, "2026-03-09T10:00:30Z", "1", "1", "1", true))

  // A buy of 0.1 BTC for 5000 USDT, which needs no price.
  private val bitcoinBuy = trade("BTCUSDT", 1, "2026-03-09T10:00:30Z", "50000", "0.1", "5000", true)

  // A buy of 1 ETH for 0.05 BTC, which no cash prices, and the minute it was made in.
  private val coinSwap = trade("ETHBTC", 1, "2026-03-09T10:00:30Z", "0.05", "1.0", "0.05", true)
  private val swapMinute = "2026-03-09T10:00:00Z"

  private final case class Fixture(
    api: FakeBinanceApi,
    records: FakeBinanceRecordStorage,
    pairStorage: FakeBinancePairStorage,
    prices: FakeBinanceMinutePriceStorage,
    instruments: FakeBrokerInstrumentStorage,
    alerts: FakeSystemAlerts,
    events: EventLog,
    clock: SteppingClock,
    service: BinanceSyncService[IO],
    restart: IO[BinanceSyncService[IO]]
  ) {
    def nextHour: IO[Unit] = clock.advance(Duration.ofHours(1))

    def calls: IO[List[Call]] = api.calls

    def tradeReads: IO[List[(String, Long)]] = calls.map(BinanceFakes.tradeReads)

    // The operations stored for the account, as every reader sees them.
    def shared: IO[Vector[AccountOperation]] =
      records.operations(accountId, Instant.EPOCH, now.plus(Duration.ofDays(365)))

    // From now on Binance reports what the stored operations leave, as it does for an account whose
    // history is complete: each coin's position, and the cash in each currency.
    def reportsRecordedBalances: IO[Unit] =
      shared.flatMap { operations =>
        val coins = Holdings.of(operations).map { case (key, quantity) =>
          asset(key.id.value) -> quantity
        }
        val cash = AccountHoldings.cashOf(operations).map { case (currency, amount) =>
          asset(currency.value) -> amount
        }
        api.setBalances(coins ++ cash)
      }
  }

  private def fixture(
    script: ApiScript = ApiScript(pairs = listed),
    config: BinanceSyncConfig = config,
    stored: List[BinanceRecord] = Nil,
    storedPairs: List[BinancePair] = Nil,
    storedPrices: Map[BinanceMinute, BigDecimal] = Map.empty,
    translations: List[AccountOperation] = Nil
  ): IO[Fixture] =
    for {
      stepping <- steppingClock(now)
      events <- eventLog
      api <- binanceApi(script)
      records <- recordStorage(stored, translations, Some(events))
      pairs <- pairStorage(storedPairs, Some(events))
      prices <- minutePriceStorage(storedPrices)
      instruments <- MarketDataFakes.brokerInstrumentStorage()
      alerts <- AlertFakes.systemAlerts()
      create = {
        implicit val clock: Clock[IO] = stepping.clock
        BinanceReconciler
          .create[IO, IO](records, alerts, config.cashAssets)
          .flatMap(reconciler =>
            BinanceSyncServiceImpl.create[IO, IO](
              account,
              config,
              api,
              records,
              pairs,
              prices,
              loggedInstruments(instruments, events),
              alerts,
              reconciler
            )
          )
      }
      service <- create
    } yield Fixture(
      api,
      records,
      pairs,
      prices,
      instruments,
      alerts,
      events,
      stepping,
      service,
      create
    )

  // Notes in the log of events that an instrument was written.
  private def loggedInstruments(
    storage: BrokerInstrumentStorage[IO],
    events: EventLog
  ): BrokerInstrumentStorage[IO] =
    new BrokerInstrumentStorage[IO] {
      def find(instrument: InstrumentKey): IO[Option[BrokerInstrument]] = storage.find(instrument)

      def upsert(instrument: BrokerInstrument): IO[Unit] =
        events.update(_ :+ "instruments") >> storage.upsert(instrument)
    }

  // The alerts about records no price values, apart from the ones about the balances.
  private def unvaluedAlerts(alerts: List[RaisedSystemAlert]): List[RaisedSystemAlert] =
    alerts.filter(_.name.startsWith("binance-unvalued"))

  // A buy of 0.2 BTC for 10000 USDT with a commission of 0.01 BNB, and the pair it was made on.
  private val chargedBuy = record(
    accountId,
    BinanceSource.Trade,
    trade("BTCUSDT", 1, "2026-03-09T10:00:30Z", "50000", "0.2", "10000", true, "0.01", "BNB")
  )
  private val bitcoinPair = List(pairOf("BTC", "USDT"))
  private val bnbClose = Map(minute("BNBUSDT", swapMinute) -> BigDecimal(600))

  "BinanceSyncServiceImpl" >> {
    "sync" >> {
      "the key" >> {
        "is refused before anything else is read when it can do more than read" >> {
          val dangerous =
            readOnlyKey.copy(otherFlags = readOnlyKey.otherFlags.updated("enableWithdrawals", true))
          for {
            f <- fixture(ApiScript(permissions = dangerous, pairs = listed))
            failure <- f.service.sync.attempt
            calls <- f.calls
          } yield {
            failure must beLeft[Throwable](
              BinanceKeyError.MoreThanReading(List("enableWithdrawals"))
            )
            calls must_== List(Call.Restrictions)
          }
        }

        "is refused when it may not read" >> {
          for {
            f <- fixture(ApiScript(permissions = readOnlyKey.copy(enableReading = false)))
            failure <- f.service.sync.attempt
            calls <- f.calls
          } yield {
            failure must beLeft[Throwable](BinanceKeyError.ReadingNotEnabled)
            calls must_== List(Call.Restrictions)
          }
        }

        "is accepted when it is not restricted to an IP address" >> {
          for {
            f <- fixture(
              ApiScript(permissions = readOnlyKey.copy(ipRestricted = false), pairs = listed)
            )
            outcome <- f.service.sync.attempt
          } yield outcome must beRight(())
        }
      }

      "the balances" >> {
        "are read right after the exchange information, the spot wallet first, before any record" >> {
          for {
            f <- fixture()
            _ <- f.service.sync
            calls <- f.calls
          } yield calls.take(4) must_==
            List(Call.Restrictions, Call.ExchangeInfo, Call.SpotBalances, Call.FundingBalances)
        }

        "that cannot be read fail the run before any record is read" >> {
          val boom = new RuntimeException("funding unavailable")
          val script = ApiScript(pairs = listed, failures = { case Call.FundingBalances => boom })
          for {
            f <- fixture(script)
            failure <- f.service.sync.attempt
            calls <- f.calls
          } yield {
            failure must beLeft[Throwable](boom)
            calls.collect { case window: Call.Window => window } must beEmpty
          }
        }
      }

      "the windows" >> {
        "of the first run reach from the start of the history to now, each ending a millisecond before the next starts" >> {
          for {
            f <- fixture()
            _ <- f.service.sync
            calls <- f.calls
          } yield {
            windowsRead(calls, BinanceSource.Deposit) must_== List(
              (at("2025-10-01T00:00:00Z"), at("2025-12-28T23:59:59.999Z")),
              (at("2025-12-29T00:00:00Z"), now)
            )
            longWindowSources.map(windowsRead(calls, _)).distinct.size must_== 1
          }
        }

        "of Convert are 30 days long, and follow one another without a gap" >> {
          for {
            f <- fixture()
            _ <- f.service.sync
            calls <- f.calls
          } yield {
            val windows = windowsRead(calls, BinanceSource.Convert)
            windows.size must_== 6
            windows.head must_== (at("2025-10-01T00:00:00Z"), at("2025-10-30T23:59:59.999Z"))
            windows.last._2 must_== now
            windows.zip(windows.tail).forall { case ((_, end), (start, _)) =>
              end.plusMillis(1) == start
            } must beTrue
          }
        }

        "of a later run of the same process start 7 days before where the run before ended" >> {
          for {
            f <- fixture()
            _ <- f.service.sync
            _ <- f.nextHour
            _ <- f.service.sync
            calls <- f.calls
          } yield {
            windowsRead(calls, BinanceSource.Deposit).drop(2) must_==
              List((at("2026-03-03T12:00:00Z"), at("2026-03-10T13:00:00Z")))
            windowsRead(calls, BinanceSource.Convert).drop(6) must_==
              List((at("2026-03-03T12:00:00Z"), at("2026-03-10T13:00:00Z")))
          }
        }

        "of a new process start 7 days before the latest record stored for the source" >> {
          val stored = List(
            record(
              accountId,
              BinanceSource.Deposit,
              deposit("d1", "BTC", "0.1", "2026-03-08T00:00:00Z")
            )
          )
          for {
            f <- fixture(stored = stored)
            _ <- f.service.sync
            calls <- f.calls
          } yield {
            windowsRead(calls, BinanceSource.Deposit) must_==
              List((at("2026-03-01T00:00:00Z"), now))
            // nothing is stored for withdrawals, which are read from the start of the history
            windowsRead(calls, BinanceSource.Withdrawal).size must_== 2
          }
        }

        "of a run that follows a failed one start where the process had read to" >> {
          // the run fails on its trades, after it has read every other source
          val failing = ApiScript(pairs = listed, failures = failingTrades("BTCUSDT"))
          for {
            f <- fixture(failing)
            _ <- f.service.sync.attempt
            _ <- f.nextHour
            _ <- f.service.sync.attempt
            calls <- f.calls
          } yield windowsRead(calls, BinanceSource.Deposit).drop(2) must_==
            List((at("2026-03-03T12:00:00Z"), at("2026-03-10T13:00:00Z")))
        }
      }

      "the records of the sources other than trades" >> {
        "are recorded with their translations" >> {
          val script = ApiScript(
            pairs = listed,
            history = Map(
              BinanceSource.Deposit -> List(deposit("d1", "BTC", "0.5", "2026-03-09T08:00:00Z")),
              BinanceSource.FiatDeposit ->
                List(fiatOrder("O1", "EUR", "100", "99", "1", "2026-03-09T09:00:00Z")),
              BinanceSource.Convert ->
                List(convert("1001", "USDT", "30", "BNB", "0.05", "2026-03-09T10:00:00Z"))
            )
          )
          for {
            f <- fixture(script)
            _ <- f.service.sync
            stored <- f.records.storedRecords
            operations <- f.shared
          } yield {
            recordRefs(stored).toSet must_== Set("deposit/d1", "fiat_deposit/O1", "convert/1001")
            operationRefs(operations).toSet must_==
              Set("deposit/d1", "fiat_deposit/O1", "fiat_deposit/O1/fee", "convert/1001")
          }
        }

        "that a read gives twice are recorded once" >> {
          val twice = deposit("d1", "BTC", "0.5", "2026-03-09T08:00:00Z")
          val script =
            ApiScript(pairs = listed, history = Map(BinanceSource.Deposit -> List(twice, twice)))
          for {
            f <- fixture(script)
            _ <- f.service.sync
            sizes <- f.records.recordedBatchSizes
          } yield sizes must_== List(1)
        }

        "are recorded in batches of 500" >> {
          val many =
            (1 to 1001).toList.map(i => deposit(s"d$i", "BTC", "0.01", "2026-03-09T08:00:00Z"))
          for {
            f <- fixture(ApiScript(pairs = listed, history = Map(BinanceSource.Deposit -> many)))
            _ <- f.service.sync
            sizes <- f.records.recordedBatchSizes
          } yield sizes must_== List(500, 500, 1)
        }

        "that cannot be translated fail the run and record nothing" >> {
          val broken = obj(s"""{"id":"d1","coin":"BTC","status":1,"insertTime":${at(
              "2026-03-09T08:00:00Z"
            ).toEpochMilli}}""")
          val script =
            ApiScript(pairs = listed, history = Map(BinanceSource.Deposit -> List(broken)))
          for {
            f <- fixture(script)
            failure <- f.service.sync.attempt
            stored <- f.records.storedRecords
          } yield {
            failure must beLeft[Throwable](
              BinanceRecordError
                .MissingField(BinanceSource.Deposit, Some(BinanceRecordId("d1")), "amount")
            )
            stored must beEmpty
          }
        }
      }

      "the pairs whose trades are read" >> {
        "are all the listed ones on the first run, each from the first trade" >> {
          for {
            f <- fixture()
            _ <- f.service.sync
            reads <- f.tradeReads
          } yield reads must_== symbolNames(listed).sorted.map(_ -> 0L)
        }

        "are, on a later run, those with stored trades, each from the id after the last stored" >> {
          val script = ApiScript(
            pairs = listed,
            trades = tradesOf("BTCUSDT", 1 to 3) ++ tradesOf("ETHUSDT", 5 to 5)
          )
          for {
            f <- fixture(script)
            _ <- f.service.sync
            _ <- f.reportsRecordedBalances
            before <- f.tradeReads.map(_.size)
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(before))
          } yield reads must_== List("BTCUSDT" -> 4L, "ETHUSDT" -> 6L)
        }

        "include, on a later run, those of a coin that arrived in another way" >> {
          val script = ApiScript(pairs = listed, trades = tradesOf("BTCUSDT", 1 to 3))
          val dogecoin = deposit("d1", "DOGE", "100", "2026-03-10T12:30:00Z")
          for {
            f <- fixture(script)
            _ <- f.service.sync
            _ <- f.reportsRecordedBalances
            before <- f.tradeReads.map(_.size)
            _ <- f.api.setHistory(BinanceSource.Deposit, List(dogecoin))
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(before))
          } yield reads must_== List("BTCUSDT" -> 4L, "DOGEBTC" -> 0L, "DOGEUSDT" -> 0L)
        }

        "do not include those of a cash asset that arrived in another way" >> {
          val script = ApiScript(pairs = listed, trades = tradesOf("BTCUSDT", 1 to 3))
          val tether = deposit("d1", "USDT", "100", "2026-03-10T12:30:00Z")
          for {
            f <- fixture(script)
            _ <- f.service.sync
            _ <- f.reportsRecordedBalances
            before <- f.tradeReads.map(_.size)
            _ <- f.api.setHistory(BinanceSource.Deposit, List(tether))
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(before))
          } yield reads must_== List("BTCUSDT" -> 4L)
        }

        "of a coin that arrived are read once, though the record is read again for a week" >> {
          val dogecoin = deposit("d1", "DOGE", "100", "2026-03-10T12:30:00Z")
          for {
            f <- fixture()
            _ <- f.service.sync
            _ <- f.api.setHistory(BinanceSource.Deposit, List(dogecoin))
            _ <- f.nextHour
            _ <- f.service.sync
            _ <- f.reportsRecordedBalances
            before <- f.tradeReads.map(_.size)
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(before))
          } yield reads must beEmpty
        }

        "of a coin whose deposit is still pending are read once the deposit is credited" >> {
          val pending = deposit("d1", "DOGE", "100", "2026-03-10T12:30:00Z", status = 0)
          val credited = deposit("d1", "DOGE", "100", "2026-03-10T12:30:00Z", status = 1)
          for {
            f <- fixture()
            _ <- f.service.sync
            _ <- f.api.setHistory(BinanceSource.Deposit, List(pending))
            _ <- f.nextHour
            _ <- f.service.sync
            whilePending <- f.tradeReads.map(_.size)
            _ <- f.api.setHistory(BinanceSource.Deposit, List(credited))
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(whilePending))
          } yield {
            whilePending must_== listed.size
            reads must_== List("DOGEBTC" -> 0L, "DOGEUSDT" -> 0L)
          }
        }

        "include those Binance announces it will delist, while it lists them" >> {
          val script = ApiScript(
            pairs = listed,
            trades = tradesOf("BTCUSDT", 1 to 3),
            delisting = List(symbol("ETHUSDT"), symbol("GONEUSDT"))
          )
          for {
            f <- fixture(script)
            _ <- f.service.sync
            _ <- f.reportsRecordedBalances
            before <- f.tradeReads.map(_.size)
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(before))
          } yield reads must_== List("BTCUSDT" -> 4L, "ETHUSDT" -> 0L)
        }

        "leave out a pair with stored trades that Binance no longer lists" >> {
          val stored = List(
            record(
              accountId,
              BinanceSource.Trade,
              trade("OLDUSDT", 7, "2026-01-05T10:00:00Z", "1", "1", "1", true)
            )
          )
          for {
            f <- fixture(stored = stored, storedPairs = List(pairOf("OLD", "USDT")))
            _ <- f.service.sync
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads
          } yield reads.map(_._1).contains("OLDUSDT") must beFalse
        }

        "are read in full again once the interval has passed, and not before" >> {
          for {
            f <- fixture()
            _ <- f.service.sync
            first <- f.tradeReads.map(_.size)
            _ <- f.nextHour
            _ <- f.service.sync
            second <- f.tradeReads.map(_.size)
            _ <- f.clock.advance(Duration.ofHours(24))
            _ <- f.service.sync
            third <- f.tradeReads.map(_.size)
          } yield {
            first must_== listed.size
            // no trade is stored, so an hour later no pair is read
            second must_== first
            third must_== first + listed.size
          }
        }

        "are read in full again after a run that failed before it had read them all" >> {
          val failing = ApiScript(pairs = listed, failures = failingTrades("ETHUSDT"))
          for {
            f <- fixture(failing)
            _ <- f.service.sync.attempt
            first <- f.tradeReads
            _ <- f.nextHour
            _ <- f.service.sync.attempt
            second <- f.tradeReads.map(_.drop(first.size))
          } yield {
            first.map(_._1) must_== List(
              "BNBUSDC",
              "BNBUSDT",
              "BTCUSDC",
              "BTCUSDT",
              "DOGEBTC",
              "DOGEUSDT",
              "ETHBTC",
              "ETHUSDT"
            )
            second must_== first
          }
        }
      }

      "the coins whose balance differs from the records" >> {
        val script = ApiScript(pairs = listed, trades = tradesOf("BTCUSDT", 1 to 3))

        // The run after a first one that has read the three trades, with Binance reporting the
        // coins and cash those leave and `besides`.
        def laterRun(f: Fixture, besides: Map[BinanceAsset, BigDecimal]): IO[List[(String, Long)]] =
          for {
            before <- f.tradeReads.map(_.size)
            _ <- f.api.setBalances(
              Map(asset("BTC") -> BigDecimal(3), asset("USDT") -> BigDecimal(-3)) ++ besides
            )
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(before))
          } yield reads

        "have their listed pairs read in the run, with the pairs of the trades already read" >> {
          for {
            f <- fixture(script)
            _ <- f.service.sync
            reads <- laterRun(f, Map(asset("DOGE") -> BigDecimal(10)))
          } yield reads must_== List("BTCUSDT" -> 4L, "DOGEBTC" -> 0L, "DOGEUSDT" -> 0L)
        }

        "have their pairs read once while the difference stays the same, and again when it changes" >> {
          for {
            f <- fixture(script)
            _ <- f.service.sync
            _ <- laterRun(f, Map(asset("DOGE") -> BigDecimal(10)))
            same <- laterRun(f, Map(asset("DOGE") -> BigDecimal(10)))
            changed <- laterRun(f, Map(asset("DOGE") -> BigDecimal(12)))
          } yield {
            same must_== List("BTCUSDT" -> 4L)
            changed must_== List("BTCUSDT" -> 4L, "DOGEBTC" -> 0L, "DOGEUSDT" -> 0L)
          }
        }

        "have their listed pairs read when the account recorded the coin and Binance does not report it" >> {
          for {
            f <- fixture(script)
            // The first run ends with the balances matching what it recorded.
            _ <- f.api.setBalances(
              Map(asset("BTC") -> BigDecimal(3), asset("USDT") -> BigDecimal(-3))
            )
            _ <- f.service.sync
            before <- f.tradeReads.map(_.size)
            _ <- f.api.setBalances(Map(asset("USDT") -> BigDecimal(-3)))
            _ <- f.nextHour
            _ <- f.service.sync
            reads <- f.tradeReads.map(_.drop(before))
          } yield reads.map(_._1) must_== List("BTCUSDC", "BTCUSDT", "DOGEBTC", "ETHBTC")
        }

        "bring no pair of a cash asset into the run" >> {
          for {
            f <- fixture(script)
            _ <- f.service.sync
            reads <- laterRun(f, Map(asset("USDT") -> BigDecimal(1000)))
          } yield reads must_== List("BTCUSDT" -> 4L)
        }

        "bring no pair of a Simple Earn token into the run" >> {
          for {
            f <- fixture(script)
            _ <- f.service.sync
            reads <- laterRun(f, Map(asset("LDBTC") -> BigDecimal(5)))
          } yield reads must_== List("BTCUSDT" -> 4L)
        }
      }

      "the trades of a pair" >> {
        "are read a page at a time up to a short page, and recorded in batches of 500" >> {
          val script = ApiScript(pairs = listed, trades = tradesOf("BTCUSDT", 1 to 1001))
          for {
            f <- fixture(script)
            _ <- f.service.sync
            reads <- f.tradeReads
            stored <- f.records.storedRecords
            sizes <- f.records.recordedBatchSizes
          } yield {
            reads.filter(_._1 == "BTCUSDT") must_== List("BTCUSDT" -> 0L, "BTCUSDT" -> 1001L)
            stored.size must_== 1001
            sizes must_== List(500, 500, 1)
          }
        }

        "are read once more after a page that is exactly full, which finds no more" >> {
          val script = ApiScript(pairs = listed, trades = tradesOf("BTCUSDT", 1 to 1000))
          for {
            f <- fixture(script)
            _ <- f.service.sync
            reads <- f.tradeReads
          } yield reads.filter(_._1 == "BTCUSDT") must_== List("BTCUSDT" -> 0L, "BTCUSDT" -> 1001L)
        }

        "that were stored are not stored again by a later run" >> {
          val script = ApiScript(pairs = listed, trades = tradesOf("BTCUSDT", 1 to 3))
          for {
            f <- fixture(script)
            _ <- f.service.sync
            _ <- f.nextHour
            _ <- f.service.sync
            sizes <- f.records.recordedBatchSizes
            stored <- f.records.storedRecords
          } yield {
            stored.size must_== 3
            sizes must_== List(3)
          }
        }
      }

      "the prices" >> {
        val script = ApiScript(
          pairs = listed,
          trades = List(coinSwap),
          closes = Map(minute("BTCUSDC", swapMinute) -> BigDecimal(60000))
        )

        "are taken from the storage when it has them, without asking Binance" >> {
          for {
            f <- fixture(
              ApiScript(pairs = listed, trades = List(coinSwap)),
              storedPrices = Map(minute("BTCUSDC", swapMinute) -> BigDecimal(60000))
            )
            _ <- f.service.sync
            calls <- f.calls
            operations <- f.shared
          } yield {
            barsRead(calls) must beEmpty
            operations.map(_.amount).filter(_.signum > 0) must_== List(BigDecimal(3000))
          }
        }

        "are read from Binance when the storage lacks them, and stored" >> {
          for {
            f <- fixture(script)
            _ <- f.service.sync
            calls <- f.calls
            stored <- f.prices.stored
            operations <- f.shared
          } yield {
            barsRead(calls) must_== List("BTCUSDC")
            stored must_== Map(minute("BTCUSDC", swapMinute) -> BigDecimal(60000))
            operations.map(_.amount).filter(_.signum > 0) must_== List(BigDecimal(3000))
          }
        }

        "are stored once for all the records of a batch" >> {
          for {
            f <- fixture(script)
            _ <- f.service.sync
            saves <- f.prices.saves
          } yield saves.map(_.size) must_== List(1)
        }

        "are tried in order, up to the first that has a bar" >> {
          val onlyUsdt = ApiScript(
            pairs = listed,
            trades = List(coinSwap),
            closes = Map(minute("BTCUSDT", swapMinute) -> BigDecimal(59990))
          )
          for {
            f <- fixture(onlyUsdt)
            _ <- f.service.sync
            calls <- f.calls
          } yield barsRead(calls) must_== List("BTCUSDC", "BTCUSDT")
        }

        "are read from Binance for an earlier candidate before a later one stored for another record is used" >> {
          for {
            f <- fixture(
              script,
              storedPrices = Map(minute("BTCUSDT", swapMinute) -> BigDecimal(59990))
            )
            _ <- f.service.sync
            calls <- f.calls
            operations <- f.shared
          } yield {
            barsRead(calls) must_== List("BTCUSDC")
            operations.map(_.amount).filter(_.signum > 0) must_== List(BigDecimal(3000))
          }
        }

        "are never asked of a pair Binance does not list" >> {
          val withoutUsdc = ApiScript(
            pairs = listed.filterNot(p => symbolNames(List(p)) == List("BTCUSDC")),
            trades = List(coinSwap),
            closes = Map(minute("BTCUSDT", swapMinute) -> BigDecimal(59990))
          )
          for {
            f <- fixture(withoutUsdc)
            _ <- f.service.sync
            calls <- f.calls
          } yield barsRead(calls) must_== List("BTCUSDT")
        }

        "are not asked for a record that needs none" >> {
          for {
            f <- fixture(ApiScript(pairs = listed, trades = List(bitcoinBuy)))
            _ <- f.service.sync
            calls <- f.calls
          } yield barsRead(calls) must beEmpty
        }

        "of a record that no bar values leave it as conversions, and raise the alert for it once" >> {
          for {
            f <- fixture(ApiScript(pairs = listed, trades = List(coinSwap)))
            _ <- f.service.sync
            calls <- f.calls
            operations <- f.shared
            raised <- f.alerts.raised.map(unvaluedAlerts)
          } yield {
            // Binance lists no ETHUSDC
            barsRead(calls) must_== List("BTCUSDC", "BTCUSDT", "ETHUSDT")
            operations.map(_.kind) must_== List(OperationKind.Conversion, OperationKind.Conversion)
            raised.map(_.name) must_== List("binance-unvalued:1")
            raised.head.body must contain("trade/ETHBTC/1")
          }
        }
      }

      "the alert for records no price values" >> {
        val unvalued = ApiScript(
          pairs = listed,
          history = Map(
            BinanceSource.Convert ->
              List(convert("1001", "BTC", "0.05", "ETH", "1", "2026-03-09T10:00:30Z"))
          )
        )

        "is raised once in a process, though the record is read again every run" >> {
          for {
            f <- fixture(unvalued)
            _ <- f.service.sync
            _ <- f.nextHour
            _ <- f.service.sync
            raised <- f.alerts.raised.map(unvaluedAlerts)
          } yield raised.map(_.name) must_== List("binance-unvalued:1")
        }

        "is raised again by a new process" >> {
          for {
            f <- fixture(unvalued)
            _ <- f.service.sync
            restarted <- f.restart
            _ <- restarted.sync
            raised <- f.alerts.raised.map(unvaluedAlerts)
          } yield raised.map(_.name) must_== List("binance-unvalued:1", "binance-unvalued:1")
        }

        "names the record, and never a key" >> {
          for {
            f <- fixture(unvalued)
            _ <- f.service.sync
            raised <- f.alerts.raised
          } yield {
            val text = raised.head.title + raised.head.body
            text must contain("convert/1001")
            text must not(contain("api-key"))
            text must not(contain("secret-key"))
          }
        }
      }

      "the alerts about the balances" >> {
        // A buy of 0.1 BTC for 5000 USDT, paid for with a deposit of as much, which leaves 0.1 BTC
        // and no cash.
        val funded = ApiScript(
          pairs = listed,
          trades = List(bitcoinBuy),
          history = Map(
            BinanceSource.Deposit -> List(deposit("d1", "USDT", "5000", "2026-03-08T00:00:00Z"))
          ),
          spot = Map(asset("BTC") -> BigDecimal("0.1"))
        )

        def reconciliations(f: Fixture): IO[List[RaisedSystemAlert]] =
          f.alerts.raised.map(_.filter(_.name.startsWith("binance-reconcil")))

        "are none while the balances match what the run recorded" >> {
          for {
            f <- fixture(funded)
            _ <- f.service.sync
            raised <- reconciliations(f)
          } yield raised must beEmpty
        }

        "raise the differences once the run has recorded what it read" >> {
          for {
            f <- fixture(funded.copy(spot = Map(asset("BTC") -> BigDecimal("0.09"))))
            _ <- f.service.sync
            raised <- reconciliations(f)
          } yield raised must_== List(
            RaisedSystemAlert(
              "binance-reconciliation:1",
              "Binance balances differ: account 1",
              "Recorded balances of account 1 differ from the balances Binance reports as of " +
                "2026-03-10T12:00:00Z:\nBTC: recorded 0.1, Binance 0.09"
            )
          )
        }

        "do not count a deposit that the run read and that Binance already reports" >> {
          val deposited = ApiScript(
            pairs = listed,
            history = Map(
              BinanceSource.Deposit -> List(deposit("d1", "DOGE", "100", "2026-03-09T08:00:00Z"))
            ),
            spot = Map(asset("DOGE") -> BigDecimal(100))
          )
          for {
            f <- fixture(deposited)
            _ <- f.service.sync
            raised <- reconciliations(f)
          } yield raised must beEmpty
        }

        "count a record that occurred before the instant the balances were read" >> {
          val before = deposit("d1", "DOGE", "100", "2026-03-10T11:59:59Z")
          for {
            f <- fixture(
              ApiScript(pairs = listed, history = Map(BinanceSource.Deposit -> List(before)))
            )
            _ <- f.service.sync
            raised <- reconciliations(f)
          } yield raised.map(_.body) must_== List(
            "Recorded balances of account 1 differ from the balances Binance reports as of " +
              "2026-03-10T12:00:00Z:\nDOGE: recorded 100, Binance 0"
          )
        }

        "leave out a record that occurred at the instant the balances were read, which they may not hold yet" >> {
          val atAsOf = deposit("d1", "DOGE", "100", "2026-03-10T12:00:00Z")
          for {
            f <- fixture(
              ApiScript(pairs = listed, history = Map(BinanceSource.Deposit -> List(atAsOf)))
            )
            _ <- f.service.sync
            stored <- f.records.storedRecords
            raised <- reconciliations(f)
          } yield {
            stored.size must_== 1
            raised must beEmpty
          }
        }

        "are raised once while they stay the same, and again when they change" >> {
          for {
            f <- fixture(funded.copy(spot = Map(asset("BTC") -> BigDecimal("0.09"))))
            _ <- f.service.sync
            _ <- f.nextHour
            _ <- f.service.sync
            sameTwice <- reconciliations(f).map(_.size)
            _ <- f.api.setBalances(Map(asset("BTC") -> BigDecimal("0.08")))
            _ <- f.nextHour
            _ <- f.service.sync
            changed <- reconciliations(f)
          } yield {
            sameTwice must_== 1
            changed.map(_.name) must_== List("binance-reconciliation:1", "binance-reconciliation:1")
            changed.last.body must contain("BTC: recorded 0.1, Binance 0.08")
          }
        }

        "are followed by the all-clear, once, when the balances match again" >> {
          for {
            f <- fixture(funded.copy(spot = Map(asset("BTC") -> BigDecimal("0.09"))))
            _ <- f.service.sync
            _ <- f.api.setBalances(Map(asset("BTC") -> BigDecimal("0.1")))
            _ <- f.nextHour
            _ <- f.service.sync
            _ <- f.nextHour
            _ <- f.service.sync
            raised <- reconciliations(f)
          } yield raised.map(_.name) must_== List(
            "binance-reconciliation:1",
            "binance-reconciled:1"
          )
        }

        "are raised again by a new process" >> {
          for {
            f <- fixture(funded.copy(spot = Map(asset("BTC") -> BigDecimal("0.09"))))
            _ <- f.service.sync
            restarted <- f.restart
            _ <- restarted.sync
            raised <- reconciliations(f)
          } yield raised.map(_.name) must_== List(
            "binance-reconciliation:1",
            "binance-reconciliation:1"
          )
        }
      }

      "the pairs, the instruments and the records of a batch" >> {
        "are stored in that order" >> {
          for {
            f <- fixture(ApiScript(pairs = listed, trades = List(bitcoinBuy)))
            _ <- f.service.sync
            events <- f.events.get
          } yield events must_== List("pairs", "instruments", "records")
        }

        "put the pairs of the trades in the pair storage" >> {
          for {
            f <- fixture(ApiScript(pairs = listed, trades = tradesOf("BTCUSDT", 1 to 2)))
            _ <- f.service.sync
            stored <- f.pairStorage.stored
          } yield stored must_== List(pairOf("BTC", "USDT"))
        }

        "put every coin the translations name in the instrument storage, named by its pair" >> {
          val script = ApiScript(
            pairs = listed,
            trades = List(
              bitcoinBuy,
              trade("ETHUSDT", 2, "2026-03-09T10:00:30Z", "2000", "1", "2000", true)
            ),
            coinNames = Map(asset("BTC") -> "Bitcoin", asset("ETH") -> "Ethereum")
          )
          for {
            f <- fixture(script)
            _ <- f.service.sync
            stored <- f.instruments.all
          } yield stored
            .map(i => (i.instrument.id.toString, i.ticker.toString, i.name, i.currency.toString))
            .sorted must_== List(
            ("BTC", "BTCUSDC", "Bitcoin", "USDC"),
            ("ETH", "ETHUSDT", "Ethereum", "USDT")
          )
        }

        "read the coin names once, however many batches there are" >> {
          val script = ApiScript(
            pairs = listed,
            trades = tradesOf("BTCUSDT", 1 to 1001),
            coinNames = Map(asset("BTC") -> "Bitcoin")
          )
          for {
            f <- fixture(script)
            _ <- f.service.sync
            calls <- f.calls
          } yield calls.count(_ == Call.Coins) must_== 1
        }

        "read no coin names when no coin is named" >> {
          val script = ApiScript(
            pairs = listed,
            history = Map(
              BinanceSource.Deposit -> List(deposit("d1", "USDT", "100", "2026-03-09T08:00:00Z"))
            )
          )
          for {
            f <- fixture(script)
            _ <- f.service.sync
            calls <- f.calls
          } yield calls must not(contain(Call.Coins))
        }

        "name a coin by its asset code when the names cannot be read" >> {
          val script = ApiScript(
            pairs = listed,
            trades = List(bitcoinBuy),
            failures = { case Call.Coins => new RuntimeException("no names") }
          )
          for {
            f <- fixture(script)
            outcome <- f.service.sync.attempt
            stored <- f.instruments.all
            recorded <- f.records.storedRecords
          } yield {
            outcome must beRight(())
            stored.map(_.name) must_== List("BTC")
            recorded.size must_== 1
          }
        }

        "of earlier pairs stay recorded when a later pair fails to be read" >> {
          val script = ApiScript(
            pairs = listed,
            trades = tradesOf("BTCUSDT", 1 to 3),
            failures = failingTrades("ETHUSDT")
          )
          for {
            f <- fixture(script)
            _ <- f.service.sync.attempt
            stored <- f.records.storedRecords
          } yield stored.size must_== 3
        }
      }
    }

    "rebuildTranslations" >> {
      "translates every stored record again with the stored pairs and prices" >> {
        for {
          f <- fixture(
            stored = List(chargedBuy),
            storedPairs = bitcoinPair,
            storedPrices = bnbClose
          )
          _ <- f.service.rebuildTranslations
          operations <- f.shared
        } yield operations.map(shown).map(op => (op._1, op._2, op._6)) must_== List(
          ("trade/BTCUSDT/1", "buy", BigDecimal(-10000)),
          ("trade/BTCUSDT/1/fee", "fee", BigDecimal(-6)),
          ("trade/BTCUSDT/1/fee-sale", "sell", BigDecimal(6))
        )
      }

      "replaces what was stored for the record, and leaves the record as it is" >> {
        val stale = accountOperation(accountId, OperationKind.Fee, -1, at("2026-03-09T10:00:30Z"))
          .copy(ref = OperationRef("trade/BTCUSDT/1/gone"))
        for {
          f <- fixture(
            stored = List(chargedBuy),
            storedPairs = bitcoinPair,
            storedPrices = bnbClose,
            translations = List(stale)
          )
          _ <- f.service.rebuildTranslations
          operations <- f.shared
          records <- f.records.storedRecords
        } yield {
          operationRefs(operations).contains("trade/BTCUSDT/1/gone") must beFalse
          records must_== List(chargedBuy)
        }
      }

      "asks Binance for nothing" >> {
        for {
          f <- fixture(
            stored = List(chargedBuy),
            storedPairs = bitcoinPair,
            storedPrices = bnbClose
          )
          _ <- f.service.rebuildTranslations
          calls <- f.calls
        } yield calls must beEmpty
      }

      "leaves the instruments of the coins it names to the next run, which writes them" >> {
        for {
          f <- fixture(
            stored = List(chargedBuy),
            storedPairs = bitcoinPair,
            storedPrices = bnbClose
          )
          _ <- f.service.rebuildTranslations
          afterRebuild <- f.instruments.all
          _ <- f.service.sync
          afterSync <- f.instruments.all
        } yield {
          afterRebuild must beEmpty
          afterSync.map(_.ticker).toSet must_== Set(Ticker("BNBUSDC"), Ticker("BTCUSDC"))
        }
      }

      "leaves what no stored price values as it is without a price, and raises the alert for it" >> {
        for {
          f <- fixture(stored = List(chargedBuy), storedPairs = bitcoinPair)
          _ <- f.service.rebuildTranslations
          operations <- f.shared
          raised <- f.alerts.raised
        } yield {
          operations.map(_.kind) must_== List(OperationKind.Buy, OperationKind.SecurityOut)
          raised.map(_.name) must_== List("binance-unvalued:1")
        }
      }

      "raises the alert once for a record, however often it is rebuilt" >> {
        for {
          f <- fixture(stored = List(chargedBuy), storedPairs = bitcoinPair)
          _ <- f.service.rebuildTranslations
          _ <- f.service.rebuildTranslations
          raised <- f.alerts.raised
        } yield raised.size must_== 1
      }

      "fails for a trade whose pair was never stored" >> {
        for {
          f <- fixture(stored = List(chargedBuy))
          failure <- f.service.rebuildTranslations.attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceRecordError.UnknownPair => ok }
      }

      "records the translations in batches of 500" >> {
        val many = (1 to 1001).toList.map(i =>
          record(
            accountId,
            BinanceSource.Deposit,
            deposit(s"d$i", "BTC", "0.01", "2026-03-09T08:00:00Z")
          )
        )
        for {
          f <- fixture(stored = many)
          _ <- f.service.rebuildTranslations
          sizes <- f.records.recordedBatchSizes
        } yield sizes must_== List(500, 500, 1)
      }
    }
  }
}
