package st.btu.trading.pillar.analytics

import org.specs2.mutable.Specification

import java.time.LocalDate

class PerformanceSpec extends Specification {

  "Performance" >> {
    "totalReturn" >> {
      "computes last / first - 1" >> {
        Performance.totalReturn(100, 110) must_== BigDecimal(0.1)
      }

      "is 0 when first is not positive, rather than dividing by it" >> {
        Performance.totalReturn(0, 50) must_== BigDecimal(0)
        Performance.totalReturn(-10, 50) must_== BigDecimal(0)
      }
    }

    "annualized" >> {
      "leaves total unchanged when compounded over exactly one 365-day year" >> {
        Performance.annualized(BigDecimal(0.1), 365) must_== BigDecimal(0.1)
      }

      "compounds a two-year total down to its one-year rate" >> {
        // 1 + total = 1.21 = 1.1^2 over 730 days (2 years): recovers 1.1 - 1 = 0.1
        Performance.annualized(BigDecimal(0.21), 730) must_== BigDecimal(0.1)
      }

      "returns total unchanged when calendarDays is under 1" >> {
        Performance.annualized(BigDecimal(0.1), 0) must_== BigDecimal(0.1)
      }
    }

    "timeWeighted" >> {
      "chains sub-period returns, adding each flow to the prior value before measuring that period's return" >> {
        // 100 -> 110 is +10%; a 50 deposit lands, then 110+50=160 -> 176 is another +10%: TWR = 21%
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = LocalDate.of(2024, 2, 1)
        val d2 = LocalDate.of(2024, 3, 1)
        val valuations =
          Vector(Valuation(d0, 100, 0), Valuation(d1, 110, 0), Valuation(d2, 176, 50))

        Performance.timeWeighted(valuations) must_== BigDecimal(0.21)
      }

      "skips a period whose prior value plus flow is not positive" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = LocalDate.of(2024, 2, 1)
        val d2 = LocalDate.of(2024, 3, 1)
        // period 1 (100 -> 50, flow -160): base = 100 - 160 = -60, skipped entirely
        // period 2 (50 -> 55, flow 0): base = 50, return = 10%
        val valuations =
          Vector(Valuation(d0, 100, 0), Valuation(d1, 50, -160), Valuation(d2, 55, 0))

        Performance.timeWeighted(valuations) must_== BigDecimal(0.1)
      }

      "is 0 for fewer than two valuations" >> {
        Performance.timeWeighted(Vector.empty) must_== BigDecimal(0)
        Performance.timeWeighted(
          Vector(Valuation(LocalDate.of(2024, 1, 1), 100, 0))
        ) must_== BigDecimal(0)
      }
    }

    "moneyWeighted" >> {
      "computes the annualized XIRR of a two-flow example: -100 then +110 exactly one year later" >> {
        val d0 = LocalDate.of(2023, 1, 1)
        val d1 = LocalDate.of(2024, 1, 1) // 365 days after d0 (2023 is not a leap year)
        val valuations = Vector(Valuation(d0, 100, 0), Valuation(d1, 110, 0))

        Performance.moneyWeighted(valuations) must beSome(BigDecimal("0.10000000"))
      }

      "is None for fewer than two valuations" >> {
        Performance.moneyWeighted(Vector(Valuation(LocalDate.of(2024, 1, 1), 100, 0))) must beNone
      }

      "is None when the NPV never changes sign across the bisection interval" >> {
        // value(first) = 0 and value(last) > 0: every flow is >= 0, so NPV(rate) > 0 for every rate
        val d0 = LocalDate.of(2023, 1, 1)
        val d1 = LocalDate.of(2024, 1, 1)
        val valuations = Vector(Valuation(d0, 0, 0), Valuation(d1, 100, 0))

        Performance.moneyWeighted(valuations) must beNone
      }
    }
  }
}
