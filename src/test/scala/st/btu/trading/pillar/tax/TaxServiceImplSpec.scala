package st.btu.trading.pillar.tax

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Figi}
import st.btu.trading.pillar.models.market.{Bar, InstrumentInfo, Source, Ticker}
import st.btu.trading.pillar.testkit.{AnalyticsFakes, MarketDataFakes}

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

class TaxServiceImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)

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

  private def instrument(ticker: Ticker, figi: String, currency: String = "RUB"): InstrumentInfo =
    InstrumentInfo(
      ticker,
      name = None,
      instrumentType = "share",
      currency = currency,
      exchange = "MOEX",
      timezone = "UTC",
      figi = Some(figi),
      updatedAt = ZonedDateTime.now(ZoneOffset.UTC)
    )

  // A ticker priced from a source with no Tinkoff cross-reference column of its own (Yahoo - see
  // docs/storage-contracts.md): InstrumentInfo.figi is None even though the account's own
  // operations for this ticker do carry a figi.
  private def yahooInstrument(ticker: Ticker): InstrumentInfo =
    InstrumentInfo(
      ticker,
      name = None,
      instrumentType = "share",
      currency = "USD",
      exchange = "NASDAQ",
      timezone = "America/New_York",
      figi = None,
      updatedAt = ZonedDateTime.now(ZoneOffset.UTC)
    )

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
          operations <- AnalyticsFakes.tcsPortfolioOperationStorage(ops)
          instruments <- MarketDataFakes.instrumentStorage(
            Map(Ticker("AAA") -> instrument(Ticker("AAA"), "FIGI1"))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          taxService = TaxServiceImpl.create[IO](operations, instruments, priceHistory, TaxConfig())
          report <- taxService.annualReport(accountId, 2024)
        } yield {
          report.realizedGains must_== BigDecimal(800)
          report.exemptGains must_== BigDecimal(0)
          report.taxableGains must_== BigDecimal(800)
          report.dividendIncome must_== BigDecimal(50)
          report.taxesWithheld must_== BigDecimal(5)
          report.estimatedTax must_== BigDecimal(800) * BigDecimal("0.13")
          report.currency must_== "RUB"
          report.unconverted must_== 0
        }
      }

      "converts a foreign-currency realized gain using the stored FX bars" >> {
        val ops = List(
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
        val fxBar = Bar(
          Ticker("USDRUB=X"),
          LocalDate.of(2024, 1, 15),
          open = 90,
          high = 90,
          low = 90,
          close = 90,
          adjClose = 90,
          volume = 0,
          source = Source.Yahoo
        )
        for {
          operations <- AnalyticsFakes.tcsPortfolioOperationStorage(ops)
          instruments <- MarketDataFakes.instrumentStorage(
            Map(Ticker("BBB") -> instrument(Ticker("BBB"), "FIGI2", currency = "USD"))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage(List(fxBar))
          taxService = TaxServiceImpl.create[IO](operations, instruments, priceHistory, TaxConfig())
          report <- taxService.annualReport(accountId, 2024)
        } yield {
          report.realizedGains must_== BigDecimal(18000) // (700 - 500) * 90
          report.unconverted must_== 0
        }
      }
    }

    "openLots" >> {

      "returns a ticker's open lots, oldest first" >> {
        val ops = List(
          op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2019, 1, 1), Some("FIGI3"), quantity = 10),
          op("OPERATION_TYPE_BUY", -600, LocalDate.of(2020, 1, 1), Some("FIGI3"), quantity = 5)
        )
        for {
          operations <- AnalyticsFakes.tcsPortfolioOperationStorage(ops)
          instruments <- MarketDataFakes.instrumentStorage(
            Map(Ticker("CCC") -> instrument(Ticker("CCC"), "FIGI3"))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          taxService = TaxServiceImpl.create[IO](operations, instruments, priceHistory, TaxConfig())
          lots <- taxService.openLots(accountId, Ticker("CCC"))
        } yield lots must_== List(
          Lot(Figi("FIGI3"), 10, 100, LocalDate.of(2019, 1, 1)),
          Lot(Figi("FIGI3"), 5, 120, LocalDate.of(2020, 1, 1))
        )
      }

      "returns nothing for a ticker with no instrument mapping" >> {
        for {
          operations <- AnalyticsFakes.tcsPortfolioOperationStorage()
          instruments <- MarketDataFakes.instrumentStorage()
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          taxService = TaxServiceImpl.create[IO](operations, instruments, priceHistory, TaxConfig())
          lots <- taxService.openLots(accountId, Ticker("ZZZ"))
        } yield lots must beEmpty
      }

      "returns nothing for a ticker whose instrument info carries no figi, even though its operations do" >> {
        val ops =
          List(
            op("OPERATION_TYPE_BUY", -500, LocalDate.of(2019, 1, 1), Some("FIGI4"), quantity = 5)
          )
        for {
          operations <- AnalyticsFakes.tcsPortfolioOperationStorage(ops)
          instruments <- MarketDataFakes.instrumentStorage(
            Map(Ticker("DDD") -> yahooInstrument(Ticker("DDD")))
          )
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          taxService = TaxServiceImpl.create[IO](operations, instruments, priceHistory, TaxConfig())
          lots <- taxService.openLots(accountId, Ticker("DDD"))
        } yield lots must beEmpty
      }
    }
  }
}
