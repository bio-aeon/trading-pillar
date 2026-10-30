package st.btu.trading.pillar.tax

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi, TcsPortfolioOperation}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.testkit.MarketDataFakes.brokerInstrument
import st.btu.trading.pillar.testkit.{AnalyticsFakes, MarketDataFakes}

import java.time.{LocalDate, ZoneOffset}

class TaxServiceImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val usd = Currency("USD")

  private def op(
    opType: String,
    amount: BigDecimal,
    date: LocalDate,
    figi: Option[String] = None,
    quantity: Int = 0,
    currency: String = "RUB"
  ) =
    AnalyticsFakes.operation(
      accountId,
      opType,
      amount,
      date.atStartOfDay(ZoneOffset.UTC),
      figi,
      quantity,
      currency
    )

  // Five shares bought for 500 dollars and sold for 700 in 2024.
  private val dollarRoundTrip: List[TcsPortfolioOperation] =
    List(
      op(
        "OPERATION_TYPE_BUY",
        -500,
        LocalDate.of(2024, 1, 1),
        Some("FIGI2"),
        quantity = 5,
        currency = "USD"
      ),
      op(
        "OPERATION_TYPE_SELL",
        700,
        LocalDate.of(2024, 2, 1),
        Some("FIGI2"),
        quantity = 5,
        currency = "USD"
      )
    )

  private def fxBar(
    ticker: Ticker,
    date: LocalDate,
    close: BigDecimal,
    source: Source = Source.Yahoo
  ): Bar = Bar(ticker, date, close, close, close, close, close, 0, source)

  // `directory` is what the broker knows by FIGI; `settings` set individual tickers to a source,
  // the rest use the default, Yahoo.
  private def build(
    ops: List[TcsPortfolioOperation],
    directory: List[BrokerInstrument] = Nil,
    bars: List[Bar] = Nil,
    settings: List[TickerSource] = Nil,
    config: TaxConfig = TaxConfig()
  ): IO[TaxService[IO]] =
    for {
      operations <- AnalyticsFakes.tcsPortfolioOperationStorage(ops)
      instrumentDirectory <- MarketDataFakes.instrumentDirectory(directory)
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
      sources <- MarketDataFakes.marketDataSources(settings = settings)
    } yield TaxServiceImpl
      .create[IO](operations, instrumentDirectory, sources, priceHistory, config)

  "TaxServiceImpl" >> {
    "annualReport" >> {

      "builds the ledger from stored operations and reports gains, dividends and withheld tax" >> {
        val ops = List(
          op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2024, 1, 1), Some("FIGI1"), quantity = 10),
          op("OPERATION_TYPE_SELL", 1800, LocalDate.of(2024, 6, 1), Some("FIGI1"), quantity = 10),
          op("OPERATION_TYPE_DIVIDEND", 50, LocalDate.of(2024, 7, 1)),
          op("OPERATION_TYPE_DIVIDEND_TAX", -5, LocalDate.of(2024, 7, 1))
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

      "base currency" >> {

        "reports amounts in the configured base currency without converting them" >> {
          val dividend =
            op("OPERATION_TYPE_DIVIDEND", 50, LocalDate.of(2024, 7, 1), currency = "USD")
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
          op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2019, 1, 1), Some("FIGI3"), quantity = 10),
          op("OPERATION_TYPE_BUY", -600, LocalDate.of(2020, 1, 1), Some("FIGI3"), quantity = 5)
        )
        for {
          service <- build(ops, directory = List(brokerInstrument("FIGI3", Ticker("CCC"))))
          lots <- service.openLots(accountId, Ticker("CCC"))
        } yield lots must_== List(
          Lot(Figi("FIGI3"), 10, 100, LocalDate.of(2019, 1, 1)),
          Lot(Figi("FIGI3"), 5, 120, LocalDate.of(2020, 1, 1))
        )
      }

      "keeps lots of different tickers apart" >> {
        val ops = List(
          op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2019, 1, 1), Some("FIGI3"), quantity = 10),
          op("OPERATION_TYPE_BUY", -600, LocalDate.of(2020, 1, 1), Some("FIGI4"), quantity = 5)
        )
        val directory =
          List(brokerInstrument("FIGI3", Ticker("CCC")), brokerInstrument("FIGI4", Ticker("DDD")))
        for {
          service <- build(ops, directory)
          lots <- service.openLots(accountId, Ticker("DDD"))
        } yield lots must_== List(Lot(Figi("FIGI4"), 5, 120, LocalDate.of(2020, 1, 1)))
      }

      "returns the lots of every figi a ticker has, oldest first, same-day lots in figi order" >> {
        // One ticker on two boards, each with its own figi.
        val ops = List(
          op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2019, 1, 1), Some("FIGI6"), quantity = 10),
          op("OPERATION_TYPE_BUY", -600, LocalDate.of(2020, 1, 1), Some("FIGI5"), quantity = 5),
          op("OPERATION_TYPE_BUY", -150, LocalDate.of(2019, 1, 1), Some("FIGI5"), quantity = 2)
        )
        val directory =
          List(brokerInstrument("FIGI5", Ticker("EEE")), brokerInstrument("FIGI6", Ticker("EEE")))
        for {
          service <- build(ops, directory)
          lots <- service.openLots(accountId, Ticker("EEE"))
        } yield lots must_== List(
          Lot(Figi("FIGI5"), 2, 75, LocalDate.of(2019, 1, 1)),
          Lot(Figi("FIGI6"), 10, 100, LocalDate.of(2019, 1, 1)),
          Lot(Figi("FIGI5"), 5, 120, LocalDate.of(2020, 1, 1))
        )
      }

      "returns nothing for a ticker with no instrument mapping" >> {
        for {
          service <- build(Nil)
          lots <- service.openLots(accountId, Ticker("ZZZ"))
        } yield lots must beEmpty
      }

      "returns nothing for a ticker whose figi the directory does not know, even though its operations carry it" >> {
        val ops =
          List(
            op("OPERATION_TYPE_BUY", -500, LocalDate.of(2019, 1, 1), Some("FIGI4"), quantity = 5)
          )
        for {
          service <- build(ops)
          lots <- service.openLots(accountId, Ticker("DDD"))
        } yield lots must beEmpty
      }
    }
  }
}
