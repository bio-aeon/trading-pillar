package st.btu.trading.pillar.tax

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.testkit.AnalyticsFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.instrumentKey

import java.time.{Instant, LocalDate, ZoneOffset}

class TaxReportSpec extends Specification {

  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val usd = Currency("USD")
  private val eur = Currency("EUR")
  private val twd = Currency("TWD")
  private val usdt = Currency("USDT")

  private def at(date: LocalDate): Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant

  // A realized gain in roubles unless told otherwise, from a sale that cost nothing.
  private def rg(
    instrument: String,
    sellDate: LocalDate,
    gain: BigDecimal,
    holdingDays: Long,
    quantity: BigDecimal = 1,
    currency: Currency = rub
  ): RealizedGain =
    RealizedGain(
      instrumentKey(instrument),
      None,
      sellDate,
      quantity,
      currency,
      gain,
      Map.empty,
      holdingDays
    )

  // The sale of a coin for `proceeds` in `currency`, of lots that cost `costBasis` in the currencies
  // named.
  private def sale(
    sellDate: LocalDate,
    currency: Currency,
    proceeds: BigDecimal,
    costBasis: Map[Currency, BigDecimal],
    holdingDays: Long = 10
  ): RealizedGain =
    RealizedGain(
      instrumentKey("COIN"),
      None,
      sellDate,
      BigDecimal("0.1"),
      currency,
      proceeds,
      costBasis,
      holdingDays
    )

  private def op(
    kind: OperationKind,
    amount: BigDecimal,
    date: LocalDate,
    instrument: Option[String] = None,
    currency: String = "RUB",
    state: OperationState = OperationState.Executed,
    quantity: BigDecimal = 0,
    price: Option[BigDecimal] = None
  ): AccountOperation =
    AnalyticsFakes.accountOperation(
      accountId,
      kind,
      amount,
      at(date),
      instrument,
      quantity,
      currency = currency,
      state = state,
      price = price
    )

  private val noRate: (Currency, LocalDate) => Option[BigDecimal] = (_, _) => None

  "TaxReport" >> {
    "annual" >> {

      "sums realized gains and taxes them at the lower rate under the threshold" >> {
        val ledger =
          LotLedger(Map.empty, List(rg("F1", LocalDate.of(2025, 6, 1), 600, 30)), Map.empty)
        val ops = List(op(OperationKind.Buy, -1000, LocalDate.of(2024, 1, 1), Some("F1")))

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
        val ops = List(op(OperationKind.Buy, -1000, LocalDate.of(2020, 1, 1), Some("F1")))

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
        val ops = List(op(OperationKind.Buy, -1000, LocalDate.of(2020, 1, 1), Some("F1")))

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
        val ops = List(op(OperationKind.Buy, -1000, LocalDate.of(2020, 1, 1), Some("F1")))

        val report = TaxReport.annual(year, ledger, ops, noRate, TaxConfig(longTermYears = None))

        report.exemptGains must beNone
        report.taxableGains must_== BigDecimal(800) // 500 + 300
      }

      "applies the upper rate only to the slice of taxable gains above the progressive threshold" >> {
        val year = 2025
        val ledger =
          LotLedger(Map.empty, List(rg("F1", LocalDate.of(year, 6, 1), 2500, 10)), Map.empty)
        val ops = List(op(OperationKind.Buy, -1000, LocalDate.of(2024, 1, 1), Some("F1")))
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
        val usGain = rg("FUS", LocalDate.of(year, 3, 1), 100, holdingDays = 10, currency = usd)
        // Long-term AND unconvertible: must still add exactly 1 to `unconverted`, not 2 (once for the
        // realizedGains sum, again for the exemption check) - see TaxReport.annual's doc comment.
        val unconvertibleLongTermGain =
          rg("FEU", LocalDate.of(year, 4, 1), 50, holdingDays = 2000, currency = eur)
        val ledger = LotLedger(Map.empty, List(usGain, unconvertibleLongTermGain), Map.empty)
        val fxRate: (Currency, LocalDate) => Option[BigDecimal] =
          (ccy, _) => if (ccy == usd) Some(BigDecimal(90)) else None

        val report = TaxReport.annual(year, ledger, Nil, fxRate)

        report.realizedGains must_== BigDecimal(9000) // 100 * 90; the EUR gain is dropped
        // The only long-term gain could not be converted.
        report.exemptGains must_== Some(BigDecimal(0))
        report.unconverted must_== 1
      }

      "converts a gain in the currency of its sale, whatever currency later operations on the instrument are in" >> {
        val year = 2025
        val gain = rg("F1", LocalDate.of(year, 3, 1), 1000, holdingDays = 10, currency = twd)
        val laterFee =
          op(OperationKind.Fee, -3, LocalDate.of(year, 3, 2), Some("F1"), currency = "USD")
        val fxRate: (Currency, LocalDate) => Option[BigDecimal] =
          (ccy, _) => Map(twd -> BigDecimal(3), usd -> BigDecimal(90)).get(ccy)

        val report = TaxReport.annual(
          year,
          LotLedger(Map.empty, List(gain), Map.empty),
          List(laterFee),
          fxRate
        )

        report.realizedGains must_== BigDecimal(3000) // 1000 * 3, the TWD rate
        report.unconverted must_== 0
      }

      "a gain on lots paid in other currencies than the sale" >> {
        val year = 2025
        val saleDay = LocalDate.of(year, 3, 1)

        // Euros at 100 roubles and tether at 90, as of the day of the sale.
        val rates: (Currency, LocalDate) => Option[BigDecimal] =
          (ccy, date) =>
            if (date == saleDay) Map(eur -> BigDecimal(100), usdt -> BigDecimal(90)).get(ccy)
            else None

        "converts the proceeds and each cost in its own currency at the rate of the day of the sale" >> {
          // 0.1 of a coin bought for 2000 euros and sold for 2400 tether: 2400 * 90 - 2000 * 100.
          val gain = sale(saleDay, usdt, 2400, Map(eur -> BigDecimal(2000)))

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.realizedGains must_== BigDecimal(16000)
          report.unconverted must_== 0
        }

        "takes the cost in the currency of the sale off the proceeds before converting them" >> {
          // 2400 tether for lots that cost 1000 tether and 500 euros: (2400 - 1000) * 90 - 500 * 100.
          val gain =
            sale(saleDay, usdt, 2400, Map(usdt -> BigDecimal(1000), eur -> BigDecimal(500)))

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.realizedGains must_== BigDecimal(76000)
        }

        "is a loss when the cost converts to more than the proceeds" >> {
          // 2000 tether for 0.1 of a coin bought for 2000 euros: 2000 * 90 - 2000 * 100.
          val gain = sale(saleDay, usdt, 2000, Map(eur -> BigDecimal(2000)))

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.realizedGains must_== BigDecimal(-20000)
          report.taxableGains must_== BigDecimal(0)
        }

        "counts a gain with a cost that cannot be converted once, and leaves it out" >> {
          val convertible = sale(saleDay, usdt, 2400, Map(eur -> BigDecimal(2000)))
          // The cost in pounds has no rate, and the gain is a long-term one.
          val unconvertible =
            sale(saleDay, usdt, 100, Map(Currency("GBP") -> BigDecimal(50)), holdingDays = 2000)
          val ledger = LotLedger(Map.empty, List(convertible, unconvertible), Map.empty)

          val report = TaxReport.annual(year, ledger, Nil, rates)

          report.realizedGains must_== BigDecimal(16000)
          report.exemptGains must_== Some(BigDecimal(0))
          report.unconverted must_== 1
        }

        "counts a gain whose proceeds cannot be converted once, whatever its costs" >> {
          val gain = sale(saleDay, Currency("GBP"), 100, Map(eur -> BigDecimal(50)))

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.realizedGains must_== BigDecimal(0)
          report.unconverted must_== 1
        }

        "is judged long-term on the converted gain, so one that turns into a loss is not exempt" >> {
          // 2100 tether for a coin bought for 2000 euros is a gain of 100 in no single currency, but
          // 2100 * 90 - 2000 * 100 is a loss.
          val gain = sale(saleDay, usdt, 2100, Map(eur -> BigDecimal(2000)), holdingDays = 2000)

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.realizedGains must_== BigDecimal(-11000)
          report.exemptGains must_== Some(BigDecimal(0))
        }

        "is exempt when long-term and positive once converted" >> {
          val gain = sale(saleDay, usdt, 2400, Map(eur -> BigDecimal(2000)), holdingDays = 2000)

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.exemptGains must_== Some(BigDecimal(16000))
          report.taxableGains must_== BigDecimal(0)
        }

        "needs no rate for a cost of nothing" >> {
          val gain = sale(saleDay, usdt, 100, Map.empty)

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.realizedGains must_== BigDecimal(9000)
          report.unconverted must_== 0
        }

        "converts a sale in the base currency exactly, and its costs in their own" >> {
          val gain = sale(saleDay, rub, 300000, Map(eur -> BigDecimal(2000)))

          val report =
            TaxReport.annual(year, LotLedger(Map.empty, List(gain), Map.empty), Nil, rates)

          report.realizedGains must_== BigDecimal(100000) // 300000 - 2000 * 100
        }
      }

      "income and tax of the year" >> {

        val year = 2025

        "sums dividend and coupon income and the tax withheld, for the requested year only" >> {
          val dividend = op(OperationKind.Dividend, 100, LocalDate.of(year, 5, 1))
          val coupon = op(OperationKind.Coupon, 50, LocalDate.of(year, 6, 1))
          val withheld = op(OperationKind.Tax, -13, LocalDate.of(year, 5, 1))
          val lastYearDividend =
            op(OperationKind.Dividend, 9999, LocalDate.of(year - 1, 12, 31))
          val lastYearTax = op(OperationKind.Tax, -777, LocalDate.of(year - 1, 12, 31))
          val unrelatedBuy = op(OperationKind.Buy, -500, LocalDate.of(year, 1, 1), Some("F1"))
          val ops = List(dividend, coupon, withheld, lastYearDividend, lastYearTax, unrelatedBuy)

          val report = TaxReport.annual(year, LotLedger.empty, ops, noRate)

          report.dividendIncome must_== BigDecimal(100)
          report.couponIncome must_== BigDecimal(50)
          report.taxesWithheld must_== BigDecimal(13)
          report.realizedGains must_== BigDecimal(0)
          report.estimatedTax must_== BigDecimal(0)
        }

        "counts the market value of the shares of a stock dividend as dividend income, converted" >> {
          val dividend = op(OperationKind.Dividend, 100, LocalDate.of(year, 5, 1))
          // 5 shares valued at 30 dollars each, at 90 roubles to the dollar.
          val stockDividend = op(
            OperationKind.StockDividend,
            0,
            LocalDate.of(year, 6, 1),
            Some("F1"),
            currency = "USD",
            quantity = 5,
            price = Some(30)
          )
          val fxRate: (Currency, LocalDate) => Option[BigDecimal] =
            (ccy, _) => if (ccy == usd) Some(BigDecimal(90)) else None

          val report =
            TaxReport.annual(year, LotLedger.empty, List(dividend, stockDividend), fxRate)

          report.dividendIncome must_== BigDecimal(13600) // 100 + 5 * 30 * 90
          report.unconverted must_== 0
        }

        "counts no income for a stock dividend that has no market value" >> {
          val stockDividend = op(
            OperationKind.StockDividend,
            0,
            LocalDate.of(year, 6, 1),
            Some("F1"),
            currency = "EUR",
            quantity = 5
          )

          val report = TaxReport.annual(year, LotLedger.empty, List(stockDividend), noRate)

          report.dividendIncome must_== BigDecimal(0)
          report.unconverted must_== 0
        }

        "reward income" >> {
          def reward(
            date: LocalDate,
            quantity: BigDecimal,
            price: Option[BigDecimal],
            currency: String = "USD",
            state: OperationState = OperationState.Executed
          ) =
            op(OperationKind.Reward, 0, date, Some("COIN"), currency, state, quantity, price)

          val fxRate: (Currency, LocalDate) => Option[BigDecimal] =
            (ccy, _) => if (ccy == usd) Some(BigDecimal(90)) else None

          "counts the units received at their price per unit, converted on the day" >> {
            // 5 units valued at 30 dollars each, at 90 roubles to the dollar.
            val ops = List(reward(LocalDate.of(year, 6, 1), 5, Some(30)))

            val report = TaxReport.annual(year, LotLedger.empty, ops, fxRate)

            report.rewardIncome must_== BigDecimal(13500)
            report.unconverted must_== 0
          }

          "counts the cash received with no instrument as it is, converted" >> {
            val cash = op(OperationKind.Reward, 20, LocalDate.of(year, 6, 1), currency = "USD")

            val report = TaxReport.annual(year, LotLedger.empty, List(cash), fxRate)

            report.rewardIncome must_== BigDecimal(1800)
          }

          "adds up units and cash" >> {
            val ops = List(
              reward(LocalDate.of(year, 6, 1), 5, Some(30)),
              op(OperationKind.Reward, 20, LocalDate.of(year, 7, 1), currency = "USD")
            )

            TaxReport.annual(year, LotLedger.empty, ops, fxRate).rewardIncome must_==
              BigDecimal(15300)
          }

          "counts nothing for units with no price, and no unconverted amount" >> {
            val ops = List(reward(LocalDate.of(year, 6, 1), 5, None, currency = "EUR"))

            val report = TaxReport.annual(year, LotLedger.empty, ops, noRate)

            report.rewardIncome must_== BigDecimal(0)
            report.unconverted must_== 0
          }

          "counts an amount that cannot be converted once, and leaves it out" >> {
            val ops = List(
              reward(LocalDate.of(year, 6, 1), 5, Some(30)),
              reward(LocalDate.of(year, 6, 2), 5, Some(30), currency = "EUR"),
              op(OperationKind.Reward, 20, LocalDate.of(year, 7, 1), currency = "EUR")
            )

            val report = TaxReport.annual(year, LotLedger.empty, ops, fxRate)

            report.rewardIncome must_== BigDecimal(13500)
            report.unconverted must_== 2
          }

          "is the income of the requested year only, from executed rewards only" >> {
            val ops = List(
              reward(LocalDate.of(year, 6, 1), 1, Some(10)),
              reward(LocalDate.of(year - 1, 12, 31), 1, Some(999)),
              reward(LocalDate.of(year, 6, 2), 1, Some(999), state = OperationState.Canceled)
            )

            TaxReport.annual(year, LotLedger.empty, ops, fxRate).rewardIncome must_==
              BigDecimal(900)
          }

          "is left out of the estimated tax, which covers realized gains only" >> {
            val ops = List(reward(LocalDate.of(year, 6, 1), 5, Some(30)))

            val report = TaxReport.annual(year, LotLedger.empty, ops, fxRate)

            report.estimatedTax must_== BigDecimal(0)
            report.taxableGains must_== BigDecimal(0)
          }
        }

        "lowers the tax withheld by a refund" >> {
          val ops = List(
            op(OperationKind.Tax, -13, LocalDate.of(year, 5, 1)),
            op(OperationKind.Tax, -20, LocalDate.of(year, 6, 1)),
            op(OperationKind.Tax, 4, LocalDate.of(year, 12, 20))
          )

          val report = TaxReport.annual(year, LotLedger.empty, ops, noRate)

          report.taxesWithheld must_== BigDecimal(29) // 13 + 20 - 4
        }

        "is negative when the refunds of the year exceed the tax withheld in it" >> {
          val ops = List(
            op(OperationKind.Tax, -5, LocalDate.of(year, 5, 1)),
            op(OperationKind.Tax, 8, LocalDate.of(year, 12, 20))
          )

          val report = TaxReport.annual(year, LotLedger.empty, ops, noRate)

          report.taxesWithheld must_== BigDecimal(-3)
        }

        "leaves out operations that are not executed" >> {
          val ops = List(
            op(OperationKind.Dividend, 100, LocalDate.of(year, 5, 1)),
            op(
              OperationKind.Dividend,
              999,
              LocalDate.of(year, 5, 2),
              state = OperationState.Canceled
            ),
            op(OperationKind.Tax, -13, LocalDate.of(year, 5, 1)),
            op(OperationKind.Tax, -999, LocalDate.of(year, 5, 2), state = OperationState.Pending)
          )

          val report = TaxReport.annual(year, LotLedger.empty, ops, noRate)

          report.dividendIncome must_== BigDecimal(100)
          report.taxesWithheld must_== BigDecimal(13)
        }
      }
    }
  }
}
