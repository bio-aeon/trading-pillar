package st.btu.trading.pillar.analytics.portfolio

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Currency, TcsPortfolioOperation}
import st.btu.trading.pillar.models.domain.Figi.value
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.market.Ticker.value
import st.btu.trading.pillar.testkit.{AnalyticsFakes, MarketDataFakes}
import st.btu.trading.pillar.testkit.AnalyticsFakes.operation
import st.btu.trading.pillar.testkit.MarketDataFakes.brokerInstrument

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

class PortfolioValuationServiceImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val usd = Currency("USD")
  private val usdRub = Ticker("USDRUB=X")
  private val usdOnMoex = Ticker("USD000UTSTOM")

  private def dt(date: LocalDate): ZonedDateTime = date.atStartOfDay(ZoneOffset.UTC)

  // 1000 deposited, in roubles unless told otherwise, and `cost` of them spent on `quantity` units
  // of the instrument `figi`, both on `date`.
  private def depositAndBuy(
    date: LocalDate,
    figi: String,
    quantity: Int,
    cost: BigDecimal,
    currency: String = "RUB"
  ): List[TcsPortfolioOperation] =
    List(
      operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(date), currency = currency),
      operation(
        accountId,
        "OPERATION_TYPE_BUY",
        -cost,
        dt(date),
        figi = Some(figi),
        quantity = quantity,
        currency = currency
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

  // `directory` is what the broker knows by FIGI; `instruments` is the metadata stored per ticker;
  // `settings` set individual tickers to a source, the rest use the default, Yahoo.
  private def buildService(
    ops: List[TcsPortfolioOperation],
    directory: List[BrokerInstrument] = Nil,
    instruments: Map[Ticker, InstrumentInfo] = Map.empty,
    bars: List[Bar] = Nil,
    settings: List[TickerSource] = Nil,
    config: ValuationConfig = ValuationConfig()
  ): IO[PortfolioValuationService[IO]] =
    for {
      opStorage <- AnalyticsFakes.tcsPortfolioOperationStorage(ops)
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
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -500,
            dt(d1),
            figi = Some("F1"),
            quantity = 10
          ),
          operation(accountId, "OPERATION_TYPE_OUTPUT", -200, dt(d2)),
          operation(accountId, "OPERATION_TYPE_SELL", 240, dt(d3), figi = Some("F1"), quantity = 4)
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
          series.unmappedFigis must beEmpty
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

      "counts an unmapped FIGI and an unpriced mapped instrument, excluding both from value" >> {
        val d0 = LocalDate.of(2026, 4, 1)
        val ddd = Ticker("DDD")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -50,
            dt(d0),
            figi = Some("UNMAPPED"),
            quantity = 3
          ),
          operation(accountId, "OPERATION_TYPE_BUY", -70, dt(d0), figi = Some("F4"), quantity = 7)
        )
        // DDD (F4) is mapped but has no stored bars in either source.

        for {
          service <- buildService(ops, List(brokerInstrument("F4", ddd)), Map(ddd -> info(ddd)))
          series <- service.dailySeries(accountId, d0, d0)
        } yield {
          series.points.map(_.value) must_== Vector(BigDecimal(880))
          series.unmappedFigis must_== Set("UNMAPPED")
          series.unpricedInstruments must_== Set("DDD")
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
            series.unmappedFigis must beEmpty
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
          val ops =
            List(operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0), currency = "USD"))
          for {
            service <- buildService(ops, config = ValuationConfig(baseCurrency = usd))
            series <- service.dailySeries(accountId, d0, d0)
          } yield {
            series.points.map(_.value) must_== Vector(BigDecimal(1000))
            series.unpricedInstruments must beEmpty
          }
        }

        "reports the configured base currency on the series and the snapshot" >> {
          val ops =
            List(operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0), currency = "USD"))
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
            operation(accountId, "OPERATION_TYPE_INPUT", 100, dt(d0), currency = "USD"),
            operation(
              accountId,
              "OPERATION_TYPE_BUY",
              -20,
              dt(d0),
              figi = Some("F7"),
              quantity = 2,
              currency = "USD"
            )
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
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -500,
            dt(d0),
            figi = Some("F1"),
            quantity = 10
          ),
          operation(accountId, "OPERATION_TYPE_BUY", -300, dt(d0), figi = Some("F2"), quantity = 3)
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
          byTicker("AAA").quantity must_== 10
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

      "treats OPERATION_TYPE_INPUT_SECURITIES as a buy-like transfer-in, increasing the position's quantity" >> {
        val d0 = LocalDate.of(2026, 7, 1)
        val aaa = Ticker("AAA")

        val ops = List(
          operation(
            accountId,
            "OPERATION_TYPE_INPUT_SECURITIES",
            0,
            dt(d0),
            figi = Some("F1"),
            quantity = 5
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
          position.quantity must_== 5
          position.value must beSome(BigDecimal(100)) // 5 * 20
          snapshot.cash must_== BigDecimal(0)
          snapshot.total must_== BigDecimal(100)
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
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -50,
            dt(d0),
            figi = Some("UNMAPPED"),
            quantity = 3
          ),
          operation(accountId, "OPERATION_TYPE_BUY", -70, dt(d0), figi = Some("F4"), quantity = 7)
        )

        for {
          service <- buildService(ops, List(brokerInstrument("F4", ddd)), Map(ddd -> info(ddd)))
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.total must_== BigDecimal(880)
          snapshot.unpriced must_== 2

          val byFigi = snapshot.positions.map(p => p.figi.value -> p).toMap
          byFigi.keySet must_== Set("UNMAPPED", "F4")

          val unmapped = byFigi("UNMAPPED")
          unmapped.ticker must beNone
          unmapped.instrumentType must beNone
          unmapped.currency must beNone
          unmapped.exchange must beNone
          unmapped.quantity must_== 3
          unmapped.price must beNone
          unmapped.value must beNone
          unmapped.weight must beNone

          val mapped = byFigi("F4")
          mapped.ticker must beSome(ddd)
          mapped.quantity must_== 7
          mapped.price must beNone
          mapped.value must beNone
          mapped.weight must beNone
        }
      }

      "excludes a fully closed position (net quantity 0) from the snapshot" >> {
        val d0 = LocalDate.of(2026, 8, 1)
        val aaa = Ticker("AAA")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -500,
            dt(d0),
            figi = Some("F1"),
            quantity = 10
          ),
          operation(accountId, "OPERATION_TYPE_SELL", 550, dt(d0), figi = Some("F1"), quantity = 10)
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
    }
  }
}
