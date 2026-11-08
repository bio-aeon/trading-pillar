package st.btu.trading.pillar.analytics.portfolio

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.domain.BrokerInstrumentId.value
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.market.Ticker.value
import st.btu.trading.pillar.testkit.{AnalyticsFakes, MarketDataFakes}
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.MarketDataFakes.{brokerInstrument, instrumentKey}

import java.time.{Instant, LocalDate, ZoneOffset, ZonedDateTime}

class PortfolioValuationServiceImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val usd = Currency("USD")
  private val usdRub = Ticker("USDRUB=X")
  private val usdOnMoex = Ticker("USD000UTSTOM")

  private def at(date: LocalDate): Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant

  private def deposit(date: LocalDate, amount: BigDecimal, currency: String = "RUB") =
    accountOperation(accountId, OperationKind.Deposit, amount, at(date), currency = currency)

  private def withdrawal(date: LocalDate, amount: BigDecimal, currency: String = "RUB") =
    accountOperation(accountId, OperationKind.Withdrawal, -amount, at(date), currency = currency)

  // `quantity` units of the instrument bought for `cost` in total.
  private def buy(
    date: LocalDate,
    instrument: String,
    quantity: BigDecimal,
    cost: BigDecimal,
    currency: String = "RUB"
  ) =
    accountOperation(
      accountId,
      OperationKind.Buy,
      -cost,
      at(date),
      Some(instrument),
      quantity,
      currency
    )

  // `quantity` units of the instrument sold for `proceeds` in total.
  private def sell(
    date: LocalDate,
    instrument: String,
    quantity: BigDecimal,
    proceeds: BigDecimal
  ) =
    accountOperation(accountId, OperationKind.Sell, proceeds, at(date), Some(instrument), -quantity)

  // 1000 deposited, in roubles unless told otherwise, and `cost` of them spent on `quantity` units
  // of the instrument, both on `date`.
  private def depositAndBuy(
    date: LocalDate,
    instrument: String,
    quantity: BigDecimal,
    cost: BigDecimal,
    currency: String = "RUB"
  ): List[AccountOperation] =
    List(deposit(date, 1000, currency), buy(date, instrument, quantity, cost, currency))

  // 1000 deposited and 5 units of F1 bought for 100, both executed, beside a buy of 3 more units
  // still pending and a deposit of 500 that was canceled, all on `date`.
  private def executedPendingAndCanceled(date: LocalDate): List[AccountOperation] =
    List(
      deposit(date, 1000),
      buy(date, "F1", quantity = 5, cost = 100),
      accountOperation(
        accountId,
        OperationKind.Buy,
        -90,
        at(date),
        Some("F1"),
        quantity = 3,
        state = OperationState.Pending
      ),
      accountOperation(
        accountId,
        OperationKind.Deposit,
        500,
        at(date),
        state = OperationState.Canceled
      )
    )

  private def bar(
    ticker: Ticker,
    date: LocalDate,
    close: BigDecimal,
    source: Source = Source.Yahoo
  ): Bar = Bar(ticker, date, close, close, close, close, close, 0L, source)

  // The metadata stored for a ticker by the sync, as a Moscow-listed share unless told otherwise,
  // read from Yahoo, the source tickers use here unless a setting says otherwise.
  private def info(
    ticker: Ticker,
    currency: String = "RUB",
    origin: SourceSetting = SourceSetting.Yahoo(None)
  ): InstrumentInfo =
    InstrumentInfo(
      ticker,
      origin,
      None,
      "share",
      Currency(currency),
      "MOEX",
      "Europe/Moscow",
      None,
      ZonedDateTime.now
    )

  // `directory` is what the broker knows by instrument id; `instruments` is the metadata stored
  // per ticker; `settings` set individual tickers to a source, the rest use the default, Yahoo.
  private def buildService(
    ops: List[AccountOperation],
    directory: List[BrokerInstrument] = Nil,
    instruments: Map[Ticker, InstrumentInfo] = Map.empty,
    bars: List[Bar] = Nil,
    settings: List[TickerSource] = Nil,
    config: ValuationConfig = ValuationConfig()
  ): IO[PortfolioValuationService[IO]] =
    for {
      opStorage <- AnalyticsFakes.accountOperationStorage(ops)
      instrumentDirectory <- MarketDataFakes.instrumentDirectory(directory)
      instrumentStorage <- MarketDataFakes.instrumentStorage(instruments)
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
      sources <- MarketDataFakes.marketDataSources(settings = settings)
    } yield PortfolioValuationServiceImpl
      .create[IO](opStorage, instrumentDirectory, instrumentStorage, sources, priceHistory, config)

  "PortfolioValuationServiceImpl" >> {
    "dailySeries" >> {

      "tracks cash, positions, invested and cashFlow across a deposit, a buy, a withdrawal and a sell" >> {
        val d0 = LocalDate.of(2026, 1, 1)
        val d1 = d0.plusDays(1)
        val d2 = d0.plusDays(2)
        val d3 = d0.plusDays(3)
        val aaa = Ticker("AAA")

        val ops = List(
          deposit(d0, 1000),
          buy(d1, "F1", quantity = 10, cost = 500),
          withdrawal(d2, 200),
          sell(d3, "F1", quantity = 4, proceeds = 240)
        )
        val bars = List(bar(aaa, d1, 60), bar(aaa, d2, 60), bar(aaa, d3, 60))

        for {
          service <- buildService(
            ops,
            List(brokerInstrument("F1", aaa)),
            Map(aaa -> info(aaa)),
            bars
          )
          series <- service.dailySeries(accountId, d0, d3)
        } yield {
          series.points.map(p => (p.date, p.value, p.invested, p.cashFlow)) must_== Vector(
            (d0, BigDecimal(1000), BigDecimal(1000), BigDecimal(1000)),
            (d1, BigDecimal(1100), BigDecimal(1000), BigDecimal(0)),
            (d2, BigDecimal(900), BigDecimal(800), BigDecimal(-200)),
            (d3, BigDecimal(900), BigDecimal(800), BigDecimal(0))
          )
          series.unpricedInstruments must beEmpty
          series.unmappedInstruments must beEmpty
        }
      }

      "carries the last known close forward across a day with no bar" >> {
        val d0 = LocalDate.of(2026, 2, 1)
        val d1 = d0.plusDays(1)
        val d2 = d0.plusDays(2)
        val bbb = Ticker("BBB")

        val ops = depositAndBuy(d0, "F2", quantity = 5, cost = 100)
        // No bar on d1: its value must reuse d0's close rather than treating BBB as unpriced.
        val bars = List(bar(bbb, d0, 20), bar(bbb, d2, 25))

        for {
          service <- buildService(
            ops,
            List(brokerInstrument("F2", bbb)),
            Map(bbb -> info(bbb)),
            bars
          )
          series <- service.dailySeries(accountId, d0, d2)
        } yield {
          series.points.map(p => (p.date, p.value)) must_== Vector(
            (d0, BigDecimal(1000)),
            (d1, BigDecimal(1000)),
            (d2, BigDecimal(1025))
          )
          series.unpricedInstruments must beEmpty
        }
      }

      "converts a USD position to the base currency using the latest USDRUB=X close" >> {
        val d0 = LocalDate.of(2026, 3, 1)
        val ccc = Ticker("CCC")

        val ops = depositAndBuy(d0, "F3", quantity = 2, cost = 100)
        val bars = List(bar(ccc, d0, 50), bar(usdRub, d0, 90))

        for {
          service <- buildService(
            ops,
            List(brokerInstrument("F3", ccc, currency = "USD")),
            Map(ccc -> info(ccc, currency = "USD")),
            bars
          )
          series <- service.dailySeries(accountId, d0, d0)
        } yield {
          // cash 900 (1000 - 100) + 2 * 50 USD * 90 RUB/USD = 900 + 9000 = 9900
          series.points.map(_.value) must_== Vector(BigDecimal(9900))
          series.unpricedInstruments must beEmpty
        }
      }

      "counts an unmapped instrument and an unpriced mapped one, excluding both from value" >> {
        val d0 = LocalDate.of(2026, 4, 1)
        val ddd = Ticker("DDD")

        val ops = List(
          deposit(d0, 1000),
          buy(d0, "UNMAPPED", quantity = 3, cost = 50),
          buy(d0, "F4", quantity = 7, cost = 70)
        )
        // DDD (F4) is mapped but has no stored bars in either source.

        for {
          service <- buildService(ops, List(brokerInstrument("F4", ddd)), Map(ddd -> info(ddd)))
          series <- service.dailySeries(accountId, d0, d0)
        } yield {
          series.points.map(_.value) must_== Vector(BigDecimal(880))
          series.unmappedInstruments must_== Set(instrumentKey("UNMAPPED"))
          series.unpricedInstruments must_== Set("DDD")
        }
      }

      "operations that count" >> {

        val d0 = LocalDate.of(2026, 9, 7)
        val aaa = Ticker("AAA")

        "includes executed operations only" >> {
          for {
            service <- buildService(
              executedPendingAndCanceled(d0),
              List(brokerInstrument("F1", aaa)),
              bars = List(bar(aaa, d0, 20))
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield
          // cash 900 + 5 * 20, with neither the pending buy nor the canceled deposit
          series.points.map(p => (p.value, p.invested)) must_==
            Vector((BigDecimal(1000), BigDecimal(1000)))
        }

        "keeps income, fees and taxes in cash without counting them as invested" >> {
          val ops = List(
            deposit(d0, 1000),
            accountOperation(accountId, OperationKind.Dividend, 50, at(d0), Some("F1")),
            accountOperation(accountId, OperationKind.Fee, -4, at(d0), Some("F1")),
            accountOperation(accountId, OperationKind.Tax, -6, at(d0), Some("F1"))
          )
          for {
            service <- buildService(ops)
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            series.points.map(p => (p.value, p.invested, p.cashFlow)) must_==
              Vector((BigDecimal(1040), BigDecimal(1000), BigDecimal(1000)))
            // F1 only paid and cost money, so no position of it is held to find a ticker for.
            series.unmappedInstruments must beEmpty
          }
        }
      }

      "instrument mapping" >> {

        "prices a holding known only to the directory, using the ticker it maps to" >> {
          val d0 = LocalDate.of(2026, 9, 1)
          val aapl = Ticker("AAPL")
          val ops = depositAndBuy(d0, "F5", quantity = 2, cost = 100)
          for {
            // No metadata is stored for AAPL, and it is set to Yahoo explicitly.
            service <- buildService(
              ops,
              directory = List(brokerInstrument("F5", aapl)),
              bars = List(bar(aapl, d0, 60)),
              settings = List(TickerSource(aapl, SourceSetting.Yahoo(None)))
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            // cash 900 (1000 - 100) + 2 * 60
            series.points.map(_.value) must_== Vector(BigDecimal(1020))
            series.unmappedInstruments must beEmpty
            series.unpricedInstruments must beEmpty
          }
        }
      }

      "price source" >> {

        val d0 = LocalDate.of(2026, 9, 2)
        val sber = Ticker("SBER")

        "reads a ticker set to Tinkoff from its Tinkoff bars even when Yahoo also has bars" >> {
          val bars = List(bar(sber, d0, 50, Source.Tinkoff), bar(sber, d0, 999, Source.Yahoo))
          for {
            service <- buildService(
              depositAndBuy(d0, "F6", quantity = 3, cost = 150),
              directory = List(brokerInstrument("F6", sber)),
              bars = bars,
              settings = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield series.points.map(_.value) must_== Vector(BigDecimal(1000)) // 850 + 3 * 50
        }

        "reads a ticker on the default source from its Yahoo bars even when Tinkoff also has bars" >> {
          val bars = List(bar(sber, d0, 10, Source.Yahoo), bar(sber, d0, 777, Source.Tinkoff))
          for {
            service <- buildService(
              depositAndBuy(d0, "F6", quantity = 4, cost = 40),
              directory = List(brokerInstrument("F6", sber)),
              bars = bars
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield series.points.map(_.value) must_== Vector(BigDecimal(1000)) // 960 + 4 * 10
        }

        "leaves a ticker unpriced instead of using the other source's bars" >> {
          for {
            service <- buildService(
              depositAndBuy(d0, "F6", quantity = 3, cost = 150),
              directory = List(brokerInstrument("F6", sber)),
              bars = List(bar(sber, d0, 999, Source.Yahoo)),
              settings = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            series.points.map(_.value) must_== Vector(BigDecimal(850))
            series.unpricedInstruments must_== Set("SBER")
          }
        }
      }

      "price currency" >> {

        val d0 = LocalDate.of(2026, 9, 3)
        val ddd = Ticker("DDD")
        val ops = depositAndBuy(d0, "F7", quantity = 2, cost = 100)
        val bars = List(bar(ddd, d0, 50), bar(usdRub, d0, 90))

        "takes it from the metadata stored for the ticker rather than from the broker" >> {
          for {
            service <- buildService(
              ops,
              directory = List(brokerInstrument("F7", ddd, currency = "RUB")),
              instruments = Map(ddd -> info(ddd, currency = "USD")),
              bars = bars
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield series.points.map(_.value) must_== Vector(BigDecimal(9900)) // 900 + 2 * 50 * 90
        }

        "falls back to the broker's currency when no metadata is stored" >> {
          for {
            service <- buildService(
              ops,
              directory = List(brokerInstrument("F7", ddd, currency = "USD")),
              bars = bars
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield series.points.map(_.value) must_== Vector(BigDecimal(9900)) // 900 + 2 * 50 * 90
        }

        "ignores metadata read from another source than the ticker's, taking the broker's currency" >> {
          // DDD is set to Tinkoff, but the metadata stored for it was read from Yahoo, in roubles;
          // the broker prices it in dollars.
          val bars = List(bar(ddd, d0, 50, Source.Tinkoff), bar(usdRub, d0, 90))
          for {
            service <- buildService(
              ops,
              directory = List(brokerInstrument("F7", ddd, currency = "USD")),
              instruments =
                Map(ddd -> info(ddd, currency = "RUB", origin = SourceSetting.Yahoo(None))),
              bars = bars,
              settings = List(TickerSource(ddd, SourceSetting.Tinkoff(None)))
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield
          // In dollars: 900 + 2 * 50 * 90. The metadata's roubles would give 900 + 2 * 50 = 1000.
          series.points.map(_.value) must_== Vector(BigDecimal(9900))
        }
      }

      "exchange rates" >> {

        val d0 = LocalDate.of(2026, 9, 4)
        val ddd = Ticker("DDD")
        val ops = depositAndBuy(d0, "F7", quantity = 2, cost = 100)
        val directory = List(brokerInstrument("F7", ddd, currency = "USD"))

        "converts through the ticker named for the currency, read from that ticker's own source" >> {
          val bars = List(
            bar(ddd, d0, 50),
            bar(usdOnMoex, d0, 95, Source.Tinkoff),
            // The same ticker on the other source and the conventional pair must both be ignored.
            bar(usdOnMoex, d0, 1, Source.Yahoo),
            bar(usdRub, d0, 90)
          )
          for {
            service <- buildService(
              ops,
              directory,
              bars = bars,
              settings =
                List(TickerSource(usdOnMoex, SourceSetting.Tinkoff(Some(ClassCode("CETS"))))),
              config = ValuationConfig(fxTickers = Map(usd -> usdOnMoex))
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            series.points.map(_.value) must_== Vector(BigDecimal(10400)) // 900 + 2 * 50 * 95
            series.unpricedInstruments must beEmpty
          }
        }

        "converts through the conventional pair, on the default source, when no ticker is named" >> {
          val bars = List(
            bar(ddd, d0, 50),
            bar(usdRub, d0, 90, Source.Yahoo),
            bar(usdRub, d0, 5, Source.Tinkoff)
          )
          for {
            service <- buildService(ops, directory, bars = bars)
            series <- service.dailySeries(accountId, d0, d0)
          } yield series.points.map(_.value) must_== Vector(BigDecimal(9900)) // 900 + 2 * 50 * 90
        }

        "leaves a currency unpriced when its rate ticker has no bars on its own source" >> {
          val bars = List(bar(ddd, d0, 50), bar(usdOnMoex, d0, 95, Source.Yahoo))
          for {
            service <- buildService(
              ops,
              directory,
              bars = bars,
              settings = List(TickerSource(usdOnMoex, SourceSetting.Tinkoff(None))),
              config = ValuationConfig(fxTickers = Map(usd -> usdOnMoex))
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            series.points.map(_.value) must_== Vector(BigDecimal(900))
            series.unpricedInstruments must_== Set("DDD")
          }
        }
      }

      "currencies" >> {

        val d0 = LocalDate.of(2026, 9, 6)
        val ddd = Ticker("DDD")

        "needs no exchange rate for cash in the configured base currency" >> {
          val ops = List(deposit(d0, 1000, currency = "USD"))
          for {
            service <- buildService(ops, config = ValuationConfig(baseCurrency = usd))
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            series.points.map(_.value) must_== Vector(BigDecimal(1000))
            series.unpricedInstruments must beEmpty
          }
        }

        "reports the configured base currency on the series and the snapshot" >> {
          val ops = List(deposit(d0, 1000, currency = "USD"))
          for {
            service <- buildService(ops, config = ValuationConfig(baseCurrency = usd))
            series <- service.dailySeries(accountId, d0, d0)
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield {
            series.currency must_== usd
            snapshot.currency must_== usd
          }
        }

        "builds the conventional rate ticker from the configured base currency" >> {
          // Two euro shares at 50 euro, cash in dollars: 900 + 2 * 50 * 1.25 = 1025.
          val ops = depositAndBuy(d0, "F7", quantity = 2, cost = 100, currency = "USD")
          val bars = List(bar(ddd, d0, 50), bar(Ticker("EURUSD=X"), d0, BigDecimal("1.25")))
          for {
            service <- buildService(
              ops,
              List(brokerInstrument("F7", ddd, currency = "EUR")),
              bars = bars,
              config = ValuationConfig(baseCurrency = usd)
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield series.points.map(_.value) must_== Vector(BigDecimal(1025))
        }

        "keeps a minor-unit code such as GBp apart from GBP, leaving the position unpriced" >> {
          val vod = Ticker("VOD")
          val ops = depositAndBuy(d0, "F9", quantity = 2, cost = 100)
          // VOD's metadata is in pence; the bars of the rate of pounds must not be used for it.
          val bars = List(bar(vod, d0, 50), bar(Ticker("GBPRUB=X"), d0, 120))
          for {
            service <- buildService(
              ops,
              List(brokerInstrument("F9", vod)),
              Map(vod -> info(vod, currency = "GBp")),
              bars
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            // Only the 900 of cash counts; as pounds the 2 shares would add 2 * 50 * 120 = 12000.
            series.points.map(_.value) must_== Vector(BigDecimal(900))
            series.unpricedInstruments must_== Set("VOD")
          }
        }

        "converts foreign-currency cash, invested amount and cash flow at the rate of a position" >> {
          val ops = List(
            deposit(d0, 100, currency = "USD"),
            buy(d0, "F7", quantity = 2, cost = 20, currency = "USD")
          )
          val bars = List(bar(ddd, d0, 10), bar(usdRub, d0, 90))
          for {
            service <- buildService(
              ops,
              List(brokerInstrument("F7", ddd, currency = "USD")),
              Map(ddd -> info(ddd, currency = "USD")),
              bars
            )
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            // cash 80 USD + 2 * 10 USD = 100 USD = 9000 RUB, deposited as 100 USD = 9000 RUB
            series.points.map(p => (p.value, p.invested, p.cashFlow)) must_==
              Vector((BigDecimal(9000), BigDecimal(9000), BigDecimal(9000)))
            series.unpricedInstruments must beEmpty
          }
        }
      }
    }

    "positionsAsOf" >> {

      "prices every position, with cash/position weights summing to 1 of the total" >> {
        val d0 = LocalDate.of(2026, 5, 1)
        val aaa = Ticker("AAA")
        val bbb = Ticker("BBB")

        val ops = List(
          deposit(d0, 1000),
          buy(d0, "F1", quantity = 10, cost = 500),
          buy(d0, "F2", quantity = 3, cost = 300)
        )
        val bars = List(bar(aaa, d0, 50), bar(bbb, d0, 100))
        val directory = List(brokerInstrument("F1", aaa), brokerInstrument("F2", bbb))
        val instruments = Map(aaa -> info(aaa), bbb -> info(bbb))

        for {
          service <- buildService(ops, directory, instruments, bars)
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.date must_== d0
          snapshot.currency must_== rub
          snapshot.cash must_== BigDecimal(200)
          snapshot.total must_== BigDecimal(1000)
          snapshot.unpriced must_== 0

          val byTicker = snapshot.positions.map(p => p.ticker.map(_.value).get -> p).toMap
          byTicker("AAA").instrument must_== instrumentKey("F1")
          byTicker("AAA").quantity must_== BigDecimal(10)
          byTicker("AAA").price must beSome(BigDecimal(50))
          byTicker("AAA").value must beSome(BigDecimal(500))
          byTicker("AAA").weight must beSome(BigDecimal(0.5))
          byTicker("AAA").instrumentType must beSome("share")
          byTicker("AAA").currency must beSome(rub)
          byTicker("AAA").exchange must beSome("MOEX")
          byTicker("BBB").value must beSome(BigDecimal(300))
          byTicker("BBB").weight must beSome(BigDecimal(0.3))

          val weightSum = snapshot.positions.flatMap(_.weight).sum + snapshot.cash / snapshot.total
          weightSum must_== BigDecimal(1)
        }
      }

      "counts executed operations only" >> {
        val d0 = LocalDate.of(2026, 5, 4)
        val aaa = Ticker("AAA")
        for {
          service <- buildService(
            executedPendingAndCanceled(d0),
            List(brokerInstrument("F1", aaa)),
            bars = List(bar(aaa, d0, 20))
          )
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          // The 5 units bought, not the 3 still pending, and cash of 1000 - 100 without the
          // canceled deposit.
          snapshot.positions.map(_.quantity) must_== Vector(BigDecimal(5))
          snapshot.cash must_== BigDecimal(900)
        }
      }

      "lists the positions ordered by instrument" >> {
        val d0 = LocalDate.of(2026, 5, 2)
        val aaa = Ticker("AAA")
        val bbb = Ticker("BBB")
        // Bought in the opposite order, and under ids that sort as text, not as numbers.
        val ops = List(
          deposit(d0, 1000),
          buy(d0, "B2", quantity = 1, cost = 100),
          buy(d0, "B10", quantity = 1, cost = 100)
        )
        for {
          service <- buildService(
            ops,
            List(brokerInstrument("B2", aaa), brokerInstrument("B10", bbb)),
            bars = List(bar(aaa, d0, 100), bar(bbb, d0, 100))
          )
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield snapshot.positions.map(_.instrument.id.value) must_== Vector("B10", "B2")
      }

      "values a fractional quantity" >> {
        val d0 = LocalDate.of(2026, 5, 3)
        val aaa = Ticker("AAA")
        // Half a unit bought for 400, so at 800; it closes at 900.
        val ops = List(deposit(d0, 1000), buy(d0, "F1", quantity = BigDecimal("0.5"), cost = 400))
        for {
          service <- buildService(
            ops,
            List(brokerInstrument("F1", aaa)),
            bars = List(bar(aaa, d0, 900))
          )
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.positions.map(p => (p.quantity, p.value)) must_==
            Vector((BigDecimal("0.5"), Some(BigDecimal(450))))
          snapshot.cash must_== BigDecimal(600)
          snapshot.total must_== BigDecimal(1050)
        }
      }

      "treats a transfer of securities in as growing the position and a transfer out as shrinking it" >> {
        val d0 = LocalDate.of(2026, 7, 1)
        val aaa = Ticker("AAA")

        val ops = List(
          accountOperation(
            accountId,
            OperationKind.SecurityIn,
            0,
            at(d0),
            Some("F1"),
            quantity = 5
          ),
          accountOperation(
            accountId,
            OperationKind.SecurityOut,
            0,
            at(d0),
            Some("F1"),
            quantity = -2
          )
        )
        val bars = List(bar(aaa, d0, 20))

        for {
          service <- buildService(
            ops,
            List(brokerInstrument("F1", aaa)),
            Map(aaa -> info(aaa)),
            bars
          )
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.positions must haveSize(1)
          val position = snapshot.positions.head
          position.quantity must_== BigDecimal(3)
          position.value must beSome(BigDecimal(60)) // 3 * 20
          snapshot.cash must_== BigDecimal(0)
          snapshot.total must_== BigDecimal(60)
        }
      }

      "converts a USD position's value to the base currency while leaving price in its own currency" >> {
        val d0 = LocalDate.of(2026, 6, 1)
        val ccc = Ticker("CCC")

        val ops = depositAndBuy(d0, "F3", quantity = 2, cost = 100)
        val bars = List(bar(ccc, d0, 50), bar(usdRub, d0, 90))

        for {
          service <- buildService(
            ops,
            List(brokerInstrument("F3", ccc, currency = "USD")),
            Map(ccc -> info(ccc, currency = "USD")),
            bars
          )
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          val position = snapshot.positions.head
          position.currency must beSome(usd)
          position.price must beSome(BigDecimal(50)) // native currency, unconverted
          position.value must beSome(BigDecimal(9000)) // 2 * 50 USD * 90 RUB/USD
          snapshot.cash must_== BigDecimal(900)
          snapshot.total must_== BigDecimal(9900)
        }
      }

      "lists an unmapped and a mapped-but-unpriced position with no value, weight or price" >> {
        val d0 = LocalDate.of(2026, 7, 1)
        val ddd = Ticker("DDD")

        val ops = List(
          deposit(d0, 1000),
          buy(d0, "UNMAPPED", quantity = 3, cost = 50),
          buy(d0, "F4", quantity = 7, cost = 70)
        )

        for {
          service <- buildService(ops, List(brokerInstrument("F4", ddd)), Map(ddd -> info(ddd)))
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.total must_== BigDecimal(880)
          snapshot.unpriced must_== 2

          val byId = snapshot.positions.map(p => p.instrument.id.value -> p).toMap
          byId.keySet must_== Set("UNMAPPED", "F4")

          val unmapped = byId("UNMAPPED")
          unmapped.instrument must_== instrumentKey("UNMAPPED")
          unmapped.ticker must beNone
          unmapped.instrumentType must beNone
          unmapped.currency must beNone
          unmapped.exchange must beNone
          unmapped.quantity must_== BigDecimal(3)
          unmapped.price must beNone
          unmapped.value must beNone
          unmapped.weight must beNone

          val mapped = byId("F4")
          mapped.ticker must beSome(ddd)
          mapped.quantity must_== BigDecimal(7)
          mapped.price must beNone
          mapped.value must beNone
          mapped.weight must beNone
        }
      }

      "excludes a fully closed position (net quantity 0) from the snapshot" >> {
        val d0 = LocalDate.of(2026, 8, 1)
        val aaa = Ticker("AAA")

        val ops = List(
          deposit(d0, 1000),
          buy(d0, "F1", quantity = 10, cost = 500),
          sell(d0, "F1", quantity = 10, proceeds = 550)
        )
        val bars = List(bar(aaa, d0, 55))

        for {
          service <- buildService(
            ops,
            List(brokerInstrument("F1", aaa)),
            Map(aaa -> info(aaa)),
            bars
          )
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.positions must beEmpty
          snapshot.cash must_== BigDecimal(1050)
          snapshot.total must_== BigDecimal(1050)
          snapshot.unpriced must_== 0
        }
      }

      "describes a holding with no stored metadata from the broker's record" >> {
        val d0 = LocalDate.of(2026, 9, 5)
        val aapl = Ticker("AAPL")
        val ops = depositAndBuy(d0, "F8", quantity = 2, cost = 100)
        for {
          service <- buildService(
            ops,
            directory = List(brokerInstrument("F8", aapl)),
            bars = List(bar(aapl, d0, 60))
          )
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          val position = snapshot.positions.head
          position.ticker must beSome(aapl)
          position.name must beSome("AAPL")
          position.instrumentType must beSome("share")
          position.currency must beSome(rub)
          position.exchange must beNone
          position.value must beSome(BigDecimal(120))
        }
      }

      "source" >> {

        val d0 = LocalDate.of(2026, 10, 6)
        val aaa = Ticker("AAA")
        val sber = Ticker("SBER")

        "is the source the ticker is configured to use" >> {
          val ops = depositAndBuy(d0, "F1", quantity = 1, cost = 50) ++
            depositAndBuy(d0, "F2", quantity = 1, cost = 100)
          val bars = List(bar(aaa, d0, 50), bar(sber, d0, 100, Source.Tinkoff))
          for {
            service <- buildService(
              ops,
              directory = List(brokerInstrument("F1", aaa), brokerInstrument("F2", sber)),
              bars = bars,
              settings = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
            )
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield snapshot.positions.map(p => p.instrument.id.value -> p.source) must_== Vector(
            "F1" -> Some(Source.Yahoo),
            "F2" -> Some(Source.Tinkoff)
          )
        }

        "is absent for an unmapped instrument" >> {
          for {
            service <- buildService(depositAndBuy(d0, "UNMAPPED", quantity = 1, cost = 50))
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield snapshot.positions.map(_.source) must_== Vector(None)
        }
      }

      "day return" >> {

        val d0 = LocalDate.of(2026, 10, 5)
        val aaa = Ticker("AAA")
        val ops = depositAndBuy(d0, "F1", quantity = 10, cost = 500)

        "is the last close over the close of the bar before it, minus 1" >> {
          // d0 is a Monday, so the bar before it is the one of the Friday, three days earlier.
          val bars = List(bar(aaa, d0.minusDays(3), 50), bar(aaa, d0, 55))
          for {
            service <- buildService(ops, List(brokerInstrument("F1", aaa)), bars = bars)
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield snapshot.positions.map(_.dayReturn) must_==
            Vector(Some(BigDecimal("0.1"))) // 55 / 50 - 1
        }

        "is absent with a single close" >> {
          for {
            service <- buildService(
              ops,
              List(brokerInstrument("F1", aaa)),
              bars = List(bar(aaa, d0, 55))
            )
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield {
            val position = snapshot.positions.head
            position.price must beSome(BigDecimal(55))
            position.dayReturn must beNone
          }
        }

        "is absent when the close before is zero" >> {
          val bars = List(bar(aaa, d0.minusDays(1), 0), bar(aaa, d0, 55))
          for {
            service <- buildService(ops, List(brokerInstrument("F1", aaa)), bars = bars)
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield snapshot.positions.map(_.dayReturn) must_== Vector(None)
        }

        "is absent for an unmapped instrument" >> {
          for {
            service <- buildService(depositAndBuy(d0, "UNMAPPED", quantity = 10, cost = 500))
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield snapshot.positions.map(_.dayReturn) must_== Vector(None)
        }
      }

      "day change" >> {

        val d0 = LocalDate.of(2026, 10, 7)
        val d1 = d0.minusDays(1)
        val aaa = Ticker("AAA")
        val ccc = Ticker("CCC")
        val ddd = Ticker("DDD")

        "sums quantity times the move of the last close, converting dollars at the latest rate" >> {
          val ops = depositAndBuy(d0, "F1", quantity = 10, cost = 500) ++
            depositAndBuy(d0, "F2", quantity = 2, cost = 100) ++
            depositAndBuy(d0, "F3", quantity = 5, cost = 300)
          val directory = List(
            brokerInstrument("F1", aaa),
            brokerInstrument("F2", ccc, currency = "USD"),
            brokerInstrument("F3", ddd)
          )
          val bars = List(
            bar(aaa, d1, 50),
            bar(aaa, d0, 55),
            bar(ccc, d1, 40),
            bar(ccc, d0, 50),
            bar(usdRub, d1, 80),
            bar(usdRub, d0, 90),
            bar(ddd, d0, 60)
          )
          for {
            service <- buildService(ops, directory, bars = bars)
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield
          // AAA: 10 * (55 - 50) = 50. CCC: 2 * (50 - 40) = 20 dollars, at the latest rate of 90
          // roubles: 1800 (the day before's rate of 80 would give 1600). DDD has one close and
          // adds nothing.
          snapshot.dayChange must_== BigDecimal(1850)
        }

        "multiplies by a fractional quantity" >> {
          // Half a unit moving from 800 to 900.
          val ops = List(deposit(d0, 1000), buy(d0, "F1", quantity = BigDecimal("0.5"), cost = 400))
          for {
            service <- buildService(
              ops,
              List(brokerInstrument("F1", aaa)),
              bars = List(bar(aaa, d1, 800), bar(aaa, d0, 900))
            )
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield snapshot.dayChange must_== BigDecimal(50) // 0.5 * (900 - 800)
        }

        "is zero when no position has two closes" >> {
          for {
            service <- buildService(
              depositAndBuy(d0, "F3", quantity = 5, cost = 300),
              List(brokerInstrument("F3", ddd)),
              bars = List(bar(ddd, d0, 60))
            )
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield snapshot.dayChange must_== BigDecimal(0)
        }

        "leaves out a position whose currency has no exchange rate" >> {
          for {
            service <- buildService(
              depositAndBuy(d0, "F2", quantity = 2, cost = 100),
              List(brokerInstrument("F2", ccc, currency = "USD")),
              bars = List(bar(ccc, d1, 40), bar(ccc, d0, 50))
            )
            snapshot <- service.positionsAsOf(accountId, d0)
          } yield {
            // The position has a day return, but without a USDRUB=X bar no value to add it to.
            val position = snapshot.positions.head
            position.dayReturn must beSome(BigDecimal("0.25")) // 50 / 40 - 1
            position.value must beNone
            snapshot.dayChange must_== BigDecimal(0)
          }
        }
      }
    }
  }
}
