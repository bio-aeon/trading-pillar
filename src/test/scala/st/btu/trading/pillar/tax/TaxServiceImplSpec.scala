package st.btu.trading.pillar.tax

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.testkit.MarketDataFakes.{brokerInstrument, instrumentKey}
import st.btu.trading.pillar.testkit.TaxFakes.lot
import st.btu.trading.pillar.testkit.{AnalyticsFakes, MarketDataFakes, TaxFakes}

import java.time.{Instant, LocalDate, ZoneOffset}

class TaxServiceImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val usd = Currency("USD")
  private val eur = Currency("EUR")

  private def at(date: LocalDate): Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant

  // `quantity` is signed, positive for what grows a position.
  private def op(
    kind: OperationKind,
    amount: BigDecimal,
    date: LocalDate,
    instrument: Option[String] = None,
    quantity: BigDecimal = 0,
    currency: String = "RUB"
  ) =
    AnalyticsFakes.accountOperation(
      accountId,
      kind,
      amount,
      at(date),
      instrument,
      quantity,
      currency
    )

  // Five shares bought for 500 dollars and sold for 700 in 2024.
  private val dollarRoundTrip: List[AccountOperation] =
    List(
      op(
        OperationKind.Buy,
        -500,
        LocalDate.of(2024, 1, 1),
        Some("FIGI2"),
        quantity = 5,
        currency = "USD"
      ),
      op(
        OperationKind.Sell,
        700,
        LocalDate.of(2024, 2, 1),
        Some("FIGI2"),
        quantity = -5,
        currency = "USD"
      )
    )

  private def fxBar(
    ticker: Ticker,
    date: LocalDate,
    close: BigDecimal,
    source: Source = Source.Yahoo
  ): Bar = Bar(ticker, date, close, close, close, close, close, 0, source)

  // `directory` is what the broker knows by instrument id; `settings` set individual tickers to a
  // source, the rest use the default, Yahoo.
  private def build(
    ops: List[AccountOperation],
    directory: List[BrokerInstrument] = Nil,
    bars: List[Bar] = Nil,
    settings: List[TickerSource] = Nil,
    config: TaxConfig = TaxConfig(),
    allocations: List[CorporateActionAllocation] = Nil,
    transferCosts: Map[AccountId, List[TransferCost]] = Map.empty
  ): IO[TaxService[IO]] =
    for {
      operations <- AnalyticsFakes.accountOperationStorage(ops)
      allocationStorage <-
        TaxFakes.corporateActionAllocationStorage(Map(accountId -> allocations))
      costStorage <- TaxFakes.transferCostStorage(transferCosts)
      instrumentDirectory <- MarketDataFakes.instrumentDirectory(directory)
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
      sources <- MarketDataFakes.marketDataSources(settings = settings)
    } yield TaxServiceImpl.create[IO](
      operations,
      allocationStorage,
      costStorage,
      instrumentDirectory,
      sources,
      priceHistory,
      config
    )

  "TaxServiceImpl" >> {
    "annualReport" >> {

      "builds the ledger from stored operations and reports gains, dividends and withheld tax" >> {
        val ops = List(
          op(OperationKind.Buy, -1000, LocalDate.of(2024, 1, 1), Some("FIGI1"), quantity = 10),
          op(OperationKind.Sell, 1800, LocalDate.of(2024, 6, 1), Some("FIGI1"), quantity = -10),
          op(OperationKind.Dividend, 50, LocalDate.of(2024, 7, 1)),
          op(OperationKind.Tax, -5, LocalDate.of(2024, 7, 1))
        )
        for {
          service <- build(ops, directory = List(brokerInstrument("FIGI1", Ticker("AAA"))))
          report <- service.annualReport(accountId, 2024)
        } yield {
          report.realizedGains must_== BigDecimal(800)
          report.exemptGains must_== Some(BigDecimal(0))
          report.taxableGains must_== BigDecimal(800)
          report.dividendIncome must_== BigDecimal(50)
          report.taxesWithheld must_== BigDecimal(5)
          report.estimatedTax must_== BigDecimal(800) * BigDecimal("0.13")
          report.currency must_== rub
          report.unconverted must_== 0
        }
      }

      val usdInstruments = List(brokerInstrument("FIGI2", Ticker("BBB"), currency = "USD"))
      val rateDay = LocalDate.of(2024, 1, 15)
      val usdRub = Ticker("USDRUB=X")
      val usdOnMoex = Ticker("USD000UTSTOM")

      "exchange rates" >> {

        "converts a foreign-currency realized gain using the conventional pair's stored bars" >> {
          for {
            service <- build(
              dollarRoundTrip,
              usdInstruments,
              bars = List(fxBar(usdRub, rateDay, 90))
            )
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.realizedGains must_== BigDecimal(18000) // (700 - 500) * 90
            report.unconverted must_== 0
          }
        }

        "converts through the ticker named for the currency, read from that ticker's own source" >> {
          val bars = List(
            fxBar(usdOnMoex, rateDay, 95, Source.Tinkoff),
            // The same ticker on the other source and the conventional pair must both be ignored.
            fxBar(usdOnMoex, rateDay, 1, Source.Yahoo),
            fxBar(usdRub, rateDay, 90)
          )
          for {
            service <- build(
              dollarRoundTrip,
              usdInstruments,
              bars,
              settings =
                List(TickerSource(usdOnMoex, SourceSetting.Tinkoff(Some(ClassCode("CETS"))))),
              config = TaxConfig(fxTickers = Map(usd -> usdOnMoex))
            )
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.realizedGains must_== BigDecimal(19000) // (700 - 500) * 95
            report.unconverted must_== 0
          }
        }

        "leaves a gain unconverted when the rate ticker has bars only on another source" >> {
          for {
            service <- build(
              dollarRoundTrip,
              usdInstruments,
              bars = List(fxBar(usdRub, rateDay, 90, Source.Tinkoff))
            )
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.realizedGains must_== BigDecimal(0)
            report.unconverted must_== 1
          }
        }
      }

      "lots paid in another currency than their sale" >> {
        val saleDay = LocalDate.of(2024, 2, 1)
        val usdtRub = Ticker("USDTRUB=X")
        val eurRub = Ticker("EURRUB=X")

        // 0.1 of a coin bought for 2000 euros and sold for 2400 tether.
        val coinRoundTrip = List(
          op(
            OperationKind.Buy,
            -2000,
            LocalDate.of(2024, 1, 1),
            Some("COIN"),
            quantity = BigDecimal("0.1"),
            currency = "EUR"
          ),
          op(
            OperationKind.Sell,
            2400,
            saleDay,
            Some("COIN"),
            quantity = BigDecimal("-0.1"),
            currency = "USDT"
          )
        )

        "convert the cost in the currency it was paid in at the rate of the day of the sale" >> {
          val bars = List(
            // the rate of the day of the purchase, which must not be used
            fxBar(eurRub, LocalDate.of(2024, 1, 1), 50),
            fxBar(eurRub, LocalDate.of(2024, 1, 31), 100),
            fxBar(usdtRub, LocalDate.of(2024, 1, 31), 90)
          )
          for {
            service <- build(coinRoundTrip, bars = bars)
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.realizedGains must_== BigDecimal(16000) // 2400 * 90 - 2000 * 100
            report.unconverted must_== 0
          }
        }

        "leave the gain unconverted when the currency of the cost has no rate" >> {
          for {
            service <- build(coinRoundTrip, bars = List(fxBar(usdtRub, saleDay, 90)))
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.realizedGains must_== BigDecimal(0)
            report.unconverted must_== 1
          }
        }
      }

      "transfer costs" >> {
        val transfer =
          op(OperationKind.SecurityIn, 0, LocalDate.of(2024, 1, 1), Some("COIN"), quantity = 4)
        val sale = op(
          OperationKind.Sell,
          9000,
          LocalDate.of(2024, 2, 1),
          Some("COIN"),
          quantity = -4,
          currency = "USDT"
        )

        "are read for the report, and their currency is converted though no operation is in it" >> {
          val bars = List(
            fxBar(Ticker("EURRUB=X"), LocalDate.of(2024, 1, 31), 100),
            fxBar(Ticker("USDTRUB=X"), LocalDate.of(2024, 1, 31), 90)
          )
          for {
            service <- build(
              List(transfer, sale),
              bars = bars,
              transferCosts = Map(accountId -> List(TransferCost(transfer.ref, 6000, eur, None)))
            )
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.realizedGains must_== BigDecimal(210000) // 9000 * 90 - 6000 * 100
            report.unconverted must_== 0
          }
        }
      }

      "futures" >> {
        val directory = List(
          brokerInstrument("FIGI1", Ticker("AAA")),
          brokerInstrument("FUT1", Ticker("SFH7"), instrumentType = "futures")
        )

        "tells a future by the directory's record of the instrument" >> {
          val d0 = LocalDate.of(2024, 2, 1)
          val ops = List(
            // 10 shares bought for 1000 with a fee of 5, and sold for 3005: a gain of 2000
            op(OperationKind.Buy, -1000, LocalDate.of(2024, 1, 1), Some("FIGI1"), quantity = 10),
            op(OperationKind.Fee, -5, LocalDate.of(2024, 1, 1), Some("FIGI1")),
            op(OperationKind.Sell, 3005, LocalDate.of(2024, 6, 1), Some("FIGI1"), quantity = -10),
            // two contracts, at no cash for their price, with a fee of 10, and a margin credited
            // and one debited
            op(OperationKind.Buy, 0, d0, Some("FUT1"), quantity = 2),
            op(OperationKind.Fee, -10, d0, Some("FUT1")),
            op(OperationKind.VariationMargin, 1500, d0.plusDays(1), Some("FUT1")),
            op(OperationKind.VariationMargin, -400, d0.plusDays(2), Some("FUT1")),
            op(OperationKind.Sell, 0, d0.plusDays(3), Some("FUT1"), quantity = -2)
          )
          for {
            service <- build(ops, directory)
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.realizedGains must_== BigDecimal(2000)
            report.futuresResult must_== BigDecimal(1090) // 1500 - 400 - 10
            report.taxableGains must_== BigDecimal(3090)
            report.unconverted must_== 0
          }
        }
      }

      "base currency" >> {

        "reports amounts in the configured base currency without converting them" >> {
          val dividend =
            op(OperationKind.Dividend, 50, LocalDate.of(2024, 7, 1), currency = "USD")
          for {
            service <- build(List(dividend), config = TaxConfig(baseCurrency = usd))
            report <- service.annualReport(accountId, 2024)
          } yield {
            report.dividendIncome must_== BigDecimal(50)
            report.unconverted must_== 0
            report.currency must_== usd
          }
        }
      }
    }

    "openLots" >> {

      "returns a ticker's open lots, oldest first, found through the directory" >> {
        val ops = List(
          op(OperationKind.Buy, -1000, LocalDate.of(2019, 1, 1), Some("FIGI3"), quantity = 10),
          op(OperationKind.Buy, -600, LocalDate.of(2020, 1, 1), Some("FIGI3"), quantity = 5)
        )
        for {
          service <- build(ops, directory = List(brokerInstrument("FIGI3", Ticker("CCC"))))
          lots <- service.openLots(accountId, Ticker("CCC"))
        } yield lots must_== List(
          lot(instrumentKey("FIGI3"), 10, 100, LocalDate.of(2019, 1, 1), rub),
          lot(instrumentKey("FIGI3"), 5, 120, LocalDate.of(2020, 1, 1), rub)
        )
      }

      "keeps lots of different tickers apart" >> {
        val ops = List(
          op(OperationKind.Buy, -1000, LocalDate.of(2019, 1, 1), Some("FIGI3"), quantity = 10),
          op(OperationKind.Buy, -600, LocalDate.of(2020, 1, 1), Some("FIGI4"), quantity = 5)
        )
        val directory =
          List(brokerInstrument("FIGI3", Ticker("CCC")), brokerInstrument("FIGI4", Ticker("DDD")))
        for {
          service <- build(ops, directory)
          lots <- service.openLots(accountId, Ticker("DDD"))
        } yield lots must_== List(
          lot(instrumentKey("FIGI4"), 5, 120, LocalDate.of(2020, 1, 1), rub)
        )
      }

      "returns the lots of every instrument a ticker has, oldest first, same-day lots in instrument order" >> {
        // One ticker on two boards, each with its own instrument id.
        val ops = List(
          op(OperationKind.Buy, -1000, LocalDate.of(2019, 1, 1), Some("FIGI6"), quantity = 10),
          op(OperationKind.Buy, -600, LocalDate.of(2020, 1, 1), Some("FIGI5"), quantity = 5),
          op(OperationKind.Buy, -150, LocalDate.of(2019, 1, 1), Some("FIGI5"), quantity = 2)
        )
        val directory =
          List(brokerInstrument("FIGI5", Ticker("EEE")), brokerInstrument("FIGI6", Ticker("EEE")))
        for {
          service <- build(ops, directory)
          lots <- service.openLots(accountId, Ticker("EEE"))
        } yield lots must_== List(
          lot(instrumentKey("FIGI5"), 2, 75, LocalDate.of(2019, 1, 1), rub),
          lot(instrumentKey("FIGI6"), 10, 100, LocalDate.of(2019, 1, 1), rub),
          lot(instrumentKey("FIGI5"), 5, 120, LocalDate.of(2020, 1, 1), rub)
        )
      }

      "gives a spin-off's new instrument the share of the parent's cost that the stored allocation names" >> {
        // 10 shares of FIGI3 cost 1000, and a spin-off gives 5 shares of FIGI4, with a fifth of the cost.
        val group = Some(OperationGroup("A1"))
        val ops = List(
          op(OperationKind.Buy, -1000, LocalDate.of(2019, 1, 1), Some("FIGI3"), quantity = 10),
          op(OperationKind.Spinoff, 0, LocalDate.of(2020, 1, 1), Some("FIGI4"), quantity = 5)
            .copy(group = group),
          op(OperationKind.Spinoff, 0, LocalDate.of(2020, 1, 1), Some("FIGI3")).copy(group = group)
        )
        val directory =
          List(brokerInstrument("FIGI3", Ticker("CCC")), brokerInstrument("FIGI4", Ticker("DDD")))
        val allocations = List(
          CorporateActionAllocation(OperationGroup("A1"), instrumentKey("FIGI4"), BigDecimal("0.2"))
        )
        for {
          service <- build(ops, directory, allocations = allocations)
          parent <- service.openLots(accountId, Ticker("CCC"))
          child <- service.openLots(accountId, Ticker("DDD"))
        } yield {
          parent must_== List(lot(instrumentKey("FIGI3"), 10, 80, LocalDate.of(2019, 1, 1), rub))
          child must_== List(lot(instrumentKey("FIGI4"), 5, 40, LocalDate.of(2019, 1, 1), rub))
        }
      }

      "opens the lot of a transfer in" >> {
        val coin = instrumentKey("COIN")
        val transferDay = LocalDate.of(2024, 1, 1)
        val transfer = op(OperationKind.SecurityIn, 0, transferDay, Some("COIN"), quantity = 4)
        val directory = List(brokerInstrument("COIN", Ticker("COIN")))

        "at the cost entered for it, in the currency of the cost" >> {
          for {
            service <- build(
              List(transfer),
              directory = directory,
              transferCosts = Map(accountId -> List(TransferCost(transfer.ref, 6000, eur, None)))
            )
            lots <- service.openLots(accountId, Ticker("COIN"))
          } yield lots must_== List(lot(coin, 4, 1500, transferDay, eur))
        }

        "acquired on the day the cost names" >> {
          val earlier = LocalDate.of(2022, 6, 1)
          for {
            service <- build(
              List(transfer),
              directory = directory,
              transferCosts =
                Map(accountId -> List(TransferCost(transfer.ref, 6000, eur, Some(earlier))))
            )
            lots <- service.openLots(accountId, Ticker("COIN"))
          } yield lots must_== List(lot(coin, 4, 1500, earlier, eur))
        }

        "at no cost when the cost entered is of another account" >> {
          for {
            service <- build(
              List(transfer),
              directory = directory,
              transferCosts = Map(AccountId(2) -> List(TransferCost(transfer.ref, 6000, eur, None)))
            )
            lots <- service.openLots(accountId, Ticker("COIN"))
          } yield lots must_== List(Lot(coin, 4, Map.empty, transferDay))
        }
      }

      "returns no lot for a futures contract, whatever was bought" >> {
        val ops = List(
          op(OperationKind.Buy, 0, LocalDate.of(2024, 2, 1), Some("FUT1"), quantity = 2),
          op(OperationKind.Fee, -10, LocalDate.of(2024, 2, 1), Some("FUT1"))
        )
        val directory = List(brokerInstrument("FUT1", Ticker("SFH7"), instrumentType = "futures"))
        for {
          service <- build(ops, directory)
          lots <- service.openLots(accountId, Ticker("SFH7"))
        } yield lots must beEmpty
      }

      "returns nothing for a ticker with no instrument mapping" >> {
        for {
          service <- build(Nil)
          lots <- service.openLots(accountId, Ticker("ZZZ"))
        } yield lots must beEmpty
      }

      "returns nothing for a ticker whose instrument the directory does not know, even though its operations carry it" >> {
        val ops =
          List(op(OperationKind.Buy, -500, LocalDate.of(2019, 1, 1), Some("FIGI4"), quantity = 5))
        for {
          service <- build(ops)
          lots <- service.openLots(accountId, Ticker("DDD"))
        } yield lots must beEmpty
      }
    }
  }
}
