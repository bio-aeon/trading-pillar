package st.btu.trading.pillar.tax

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi}
import st.btu.trading.pillar.testkit.AnalyticsFakes

import java.time.{LocalDate, ZoneOffset}

class TaxReportSpec extends Specification {

  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val usd = Currency("USD")

  private def rg(
    figi: String,
    sellDate: LocalDate,
    gain: BigDecimal,
    holdingDays: Long,
    quantity: Long = 1
  ): RealizedGain =
    RealizedGain(Figi(figi), None, sellDate, quantity, gain, 0, gain, holdingDays)

  private def op(
    opType: String,
    amount: BigDecimal,
    date: LocalDate,
    figi: Option[String] = None,
    currency: String = "RUB"
  ) =
    AnalyticsFakes.operation(
      accountId,
      opType,
      amount,
      date.atStartOfDay(ZoneOffset.UTC),
      figi,
      currency = currency
    )

  private val noRate: (Currency, LocalDate) => Option[BigDecimal] = (_, _) => None

  "TaxReport" >> {
    "annual" >> {

      "sums realized gains and taxes them at the lower rate under the threshold" >> {
        val ledger =
          LotLedger(Map.empty, List(rg("F1", LocalDate.of(2025, 6, 1), 600, 30)), Map.empty)
        val ops = List(op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2024, 1, 1), Some("F1")))

        val report = TaxReport.annual(2025, ledger, ops, noRate)

        report.realizedGains must_== BigDecimal(600)
        report.exemptGains must_== Some(BigDecimal(0))
        report.taxableGains must_== BigDecimal(600)
        report.estimatedTax must_== BigDecimal(600) * BigDecimal("0.13")
        report.currency must_== rub
        report.unconverted must_== 0
      }

      "exempts a long-term positive gain but keeps a long-term loss taxable" >> {
        val year = 2025
        val longTermGain = rg("F1", LocalDate.of(year, 3, 1), 500, holdingDays = 1200)
        val longTermLoss = rg("F1", LocalDate.of(year, 4, 1), -200, holdingDays = 1200)
        val shortTermGain = rg("F1", LocalDate.of(year, 5, 1), 300, holdingDays = 100)
        val ledger =
          LotLedger(Map.empty, List(longTermGain, longTermLoss, shortTermGain), Map.empty)
        val ops = List(op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2020, 1, 1), Some("F1")))

        val report = TaxReport.annual(year, ledger, ops, noRate)

        report.realizedGains must_== BigDecimal(600) // 500 - 200 + 300
        report.exemptGains must_== Some(BigDecimal(500)) // only the long-term, positive one
        report.taxableGains must_== BigDecimal(100) // max(0, 600 - 500)
      }

      "floors taxable gains at zero when the exemption would otherwise make them negative" >> {
        val year = 2025
        val longTermGain = rg("F1", LocalDate.of(year, 3, 1), 500, holdingDays = 1200)
        val shortTermLoss = rg("F1", LocalDate.of(year, 4, 1), -900, holdingDays = 50)
        val ledger = LotLedger(Map.empty, List(longTermGain, shortTermLoss), Map.empty)
        val ops = List(op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2020, 1, 1), Some("F1")))

        val report = TaxReport.annual(year, ledger, ops, noRate)

        report.realizedGains must_== BigDecimal(-400) // 500 - 900
        report.exemptGains must_== Some(BigDecimal(500))
        report.taxableGains must_== BigDecimal(0) // max(0, -400 - 500)
        report.estimatedTax must_== BigDecimal(0)
      }

      "taxes a long-held gain like any other under a regime without a long-term exemption" >> {
        val year = 2025
        val longTermGain = rg("F1", LocalDate.of(year, 3, 1), 500, holdingDays = 1200)
        val shortTermGain = rg("F1", LocalDate.of(year, 5, 1), 300, holdingDays = 100)
        val ledger = LotLedger(Map.empty, List(longTermGain, shortTermGain), Map.empty)
        val ops = List(op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2020, 1, 1), Some("F1")))

        val report = TaxReport.annual(year, ledger, ops, noRate, TaxConfig(longTermYears = None))

        report.exemptGains must beNone
        report.taxableGains must_== BigDecimal(800) // 500 + 300
      }

      "applies the upper rate only to the slice of taxable gains above the progressive threshold" >> {
        val year = 2025
        val ledger =
          LotLedger(Map.empty, List(rg("F1", LocalDate.of(year, 6, 1), 2500, 10)), Map.empty)
        val ops = List(op("OPERATION_TYPE_BUY", -1000, LocalDate.of(2024, 1, 1), Some("F1")))
        val config = TaxConfig(
          progressiveThreshold = BigDecimal(1000),
          lowerRate = BigDecimal("0.10"),
          upperRate = BigDecimal("0.20")
        )

        val report = TaxReport.annual(year, ledger, ops, noRate, config)

        report.taxableGains must_== BigDecimal(2500)
        report.estimatedTax must_== BigDecimal(400) // 1000*0.10 + 1500*0.20
      }

      "converts a foreign-currency gain using the given fx rate, and counts an unconvertible one once, not twice" >> {
        val year = 2025
        val usGain = rg("FUS", LocalDate.of(year, 3, 1), 100, holdingDays = 10)
        // Long-term AND unconvertible: must still add exactly 1 to `unconverted`, not 2 (once for the
        // realizedGains sum, again for the exemption check) - see TaxReport.annual's doc comment.
        val unconvertibleLongTermGain = rg("FEU", LocalDate.of(year, 4, 1), 50, holdingDays = 2000)
        val ledger = LotLedger(Map.empty, List(usGain, unconvertibleLongTermGain), Map.empty)
        val ops = List(
          op("OPERATION_TYPE_BUY", -900, LocalDate.of(2024, 1, 1), Some("FUS"), currency = "USD"),
          op("OPERATION_TYPE_BUY", -900, LocalDate.of(2024, 1, 1), Some("FEU"), currency = "EUR")
        )
        val fxRate: (Currency, LocalDate) => Option[BigDecimal] =
          (ccy, _) => if (ccy == usd) Some(BigDecimal(90)) else None

        val report = TaxReport.annual(year, ledger, ops, fxRate)

        report.realizedGains must_== BigDecimal(9000) // 100 * 90; the EUR gain is dropped
        // The only long-term gain could not be converted.
        report.exemptGains must_== Some(BigDecimal(0))
        report.unconverted must_== 1
      }

      "sums dividend and coupon income and withheld tax as a positive magnitude, for the requested year only" >> {
        val year = 2025
        val dividend = op("OPERATION_TYPE_DIVIDEND", 100, LocalDate.of(year, 5, 1))
        val coupon = op("OPERATION_TYPE_COUPON", 50, LocalDate.of(year, 6, 1))
        val withheld = op("OPERATION_TYPE_DIVIDEND_TAX", -13, LocalDate.of(year, 5, 1))
        val lastYearDividend = op("OPERATION_TYPE_DIVIDEND", 9999, LocalDate.of(year - 1, 12, 31))
        val unrelatedBuy = op("OPERATION_TYPE_BUY", -500, LocalDate.of(year, 1, 1), Some("F1"))
        val ops = List(dividend, coupon, withheld, lastYearDividend, unrelatedBuy)

        val report = TaxReport.annual(year, LotLedger.empty, ops, noRate)

        report.dividendIncome must_== BigDecimal(100)
        report.couponIncome must_== BigDecimal(50)
        report.taxesWithheld must_== BigDecimal(13)
        report.realizedGains must_== BigDecimal(0)
        report.estimatedTax must_== BigDecimal(0)
      }

      "treats tax-correction and its progressive and repo siblings as withheld, but not the coupon correction variant" >> {
        val year = 2025
        def taxOp(opType: String, amount: BigDecimal) =
          op(opType, amount, LocalDate.of(year, 1, 15))
        val ops = List(
          taxOp("OPERATION_TYPE_TAX_CORRECTION", -10),
          taxOp("OPERATION_TYPE_TAX_CORRECTION_PROGRESSIVE", -20),
          taxOp("OPERATION_TYPE_TAX_REPO_HOLD", -5),
          taxOp("OPERATION_TYPE_TAX_REPO_REFUND", -3),
          taxOp("OPERATION_TYPE_TAX_CORRECTION_COUPON", -9999)
        )

        val report = TaxReport.annual(year, LotLedger.empty, ops, noRate)

        report.taxesWithheld must_== BigDecimal(
          38
        ) // 10 + 20 + 5 + 3, excluding TAX_CORRECTION_COUPON
      }
    }
  }
}
