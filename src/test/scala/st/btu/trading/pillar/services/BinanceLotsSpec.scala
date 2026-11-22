package st.btu.trading.pillar.services

import cats.data.NonEmptyList
import io.circe.JsonObject
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.services.BinancePrices.PriceBook
import st.btu.trading.pillar.services.BinanceTranslation.Context
import st.btu.trading.pillar.tax.*
import st.btu.trading.pillar.testkit.BinanceFakes.*
import st.btu.trading.pillar.testkit.TaxFakes.lot

import java.time.LocalDate

// The operations the translation gives, as the tax lots and the report carry them.
class BinanceLotsSpec extends Specification {

  private val accountId = AccountId(1)
  private val eur = Currency("EUR")
  private val usdt = Currency("USDT")
  private val rub = Currency("RUB")

  private val context = Context(
    Set("USDT", "USDC", "EUR").map(asset),
    NonEmptyList.of(asset("USDC"), asset("USDT")),
    List(pairOf("BTC", "USDT"), pairOf("DOGE", "USDT"), pairOf("BNB", "USDT"))
      .map(pair => pair.symbol -> pair)
      .toMap
  )

  private val dogecoin = InstrumentKey(Broker.Binance, BrokerInstrumentId("DOGE"))
  private val bnb = InstrumentKey(Broker.Binance, BrokerInstrumentId("BNB"))

  // Euros at 100 roubles and tether at 90, whatever the day.
  private def rates(currency: Currency, day: LocalDate): Option[BigDecimal] =
    Map(eur -> BigDecimal(100), usdt -> BigDecimal(90)).get(currency)

  private def operationsOf(
    records: List[(BinanceSource, JsonObject)],
    prices: PriceBook = PriceBook()
  ): List[AccountOperation] =
    records.flatMap { case (source, attributes) =>
      BinanceTranslation
        .translate(record(accountId, source, attributes), context, prices)
        .fold(error => throw new AssertionError(error.getMessage), _.operations)
    }

  private def ledgerOf(
    operations: List[AccountOperation],
    transferCosts: List[TransferCost] = Nil
  ): LotLedger =
    TaxLots.fifo(operations, _ => None, transferCosts = transferCosts)

  "Binance operations" >> {
    "a coin bought with euros and sold for tether" >> {
      // 0.1 of a coin bought by card for 2000 euros, and sold 36 days later for 2400 tether.
      val operations = operationsOf(
        List(
          BinanceSource.FiatBuy ->
            fiatPayment("P1", "EUR", "BTC", "2000", "0.1", "0", "20000", "2026-01-05T10:00:00Z"),
          BinanceSource.Trade ->
            trade("BTCUSDT", 1, "2026-02-10T09:00:00Z", "24000", "0.1", "2400", isBuyer = false)
        )
      )

      "keeps its cost in euros through the sale, in the ledger" >> {
        val ledger = ledgerOf(operations)

        ledger.open must beEmpty
        ledger.realized.map(r => (r.currency, r.proceeds, r.costBasis, r.holdingDays)) must_==
          List((usdt, BigDecimal(2400), Map(eur -> BigDecimal(2000)), 36L))
      }

      "has each amount of its gain converted on the day of the sale, in the report" >> {
        val report = TaxReport.annual(
          2026,
          ledgerOf(operations),
          operations,
          rates,
          TaxConfig(baseCurrency = rub)
        )

        report.realizedGains must_== BigDecimal(16000) // 2400 * 90 - 2000 * 100
        report.unconverted must_== 0
      }
    }

    "dust converted into BNB" >> {
      "carries the cost and the date of the coin given up to BNB, and realizes nothing" >> {
        val operations = operationsOf(
          List(
            BinanceSource.Trade ->
              trade("DOGEUSDT", 1, "2026-01-05T10:00:00Z", "0.25", "1200", "300", isBuyer = true),
            BinanceSource.Dust -> dustConversion(
              7,
              "2026-02-01T08:00:00Z",
              dustDetail(70, "DOGE", "1200", "0.0015", "0.00003", "2026-02-01T08:00:00Z")
            )
          )
        )

        val ledger = ledgerOf(operations)

        // 1200 units at 0.25 tether, now 0.0015 of BNB: 300 tether over 0.0015 units.
        ledger.open must_== Map(
          bnb -> List(lot(bnb, BigDecimal("0.0015"), 200000, LocalDate.of(2026, 1, 5), usdt))
        )
        ledger.realized must beEmpty
      }
    }

    "a distribution of a coin" >> {
      val operations = operationsOf(
        List(BinanceSource.Dividend -> distribution(1, "DOGE", "100", "2026-03-10T12:34:56.789Z")),
        PriceBook(found = Map(minute("DOGEUSDT", "2026-03-10T12:34:00Z") -> BigDecimal("0.2")))
      )

      "opens a lot at the price of the minute, in the currency of the pair" >> {
        ledgerOf(operations).open must_==
          Map(
            dogecoin -> List(lot(dogecoin, 100, BigDecimal("0.2"), LocalDate.of(2026, 3, 10), usdt))
          )
      }

      "counts the value of the units as reward income, converted" >> {
        val report = TaxReport.annual(
          2026,
          ledgerOf(operations),
          operations,
          rates,
          TaxConfig(baseCurrency = rub)
        )

        report.rewardIncome must_== BigDecimal(1800) // 100 * 0.2 * 90
      }
    }

    "a coin deposited from elsewhere" >> {
      // 4 units deposited, which cost 6000 euros, and sold 10 days later for 9000 tether.
      val operations = operationsOf(
        List(
          BinanceSource.Deposit -> deposit("d1", "BTC", "4", "2026-01-05T10:00:00Z"),
          BinanceSource.Trade ->
            trade("BTCUSDT", 1, "2026-01-15T09:00:00Z", "2250", "4", "9000", isBuyer = false)
        )
      )

      "costs what the owner entered for the transfer, in the currency they paid" >> {
        val cost = TransferCost(OperationRef("deposit/d1"), 6000, eur, None)

        val ledger = ledgerOf(operations, List(cost))

        ledger.realized.map(_.costBasis) must_== List(Map(eur -> BigDecimal(6000)))
        TaxReport
          .annual(2026, ledger, operations, rates, TaxConfig(baseCurrency = rub))
          .realizedGains must_== BigDecimal(210000) // 9000 * 90 - 6000 * 100
      }

      "costs nothing until the owner enters a cost, so that the whole proceeds are gain" >> {
        val ledger = ledgerOf(operations)

        ledger.realized.map(_.costBasis) must_== List(Map.empty)
        TaxReport
          .annual(2026, ledger, operations, rates, TaxConfig(baseCurrency = rub))
          .realizedGains must_== BigDecimal(810000) // 9000 * 90
      }
    }
  }
}
