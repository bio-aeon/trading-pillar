package st.btu.trading.pillar.analytics.portfolio

import org.specs2.mutable.Specification

import java.time.LocalDate

class PortfolioMetricsSpec extends Specification {

  private val day1 = LocalDate.of(2024, 1, 1)
  private val day2 = day1.plusDays(1)
  private val day3 = day1.plusDays(2)

  // 1000 -> 1100 is +10%. A 400 deposit lands on day 3, so 1100 + 400 = 1500 -> 1650 is another
  // +10%: time-weighted return = 1.1 * 1.1 - 1 = 21%. The raw values give 1650 / 1000 - 1 = 65%.
  private val withDeposit = Vector(
    ValuationPoint(day1, 1000, 1000, 1000),
    ValuationPoint(day2, 1100, 1000, 0),
    ValuationPoint(day3, 1650, 1400, 400)
  )

  // The value falls from a peak of 1000 to 600 only because 400 is withdrawn on day 3, so no money
  // is lost, yet the raw values fall (1000 - 600) / 1000 = 40%.
  private val withWithdrawal = Vector(
    ValuationPoint(day1, 1000, 1000, 1000),
    ValuationPoint(day2, 1000, 1000, 0),
    ValuationPoint(day3, 600, 600, -400)
  )

  "PortfolioMetrics" >> {
    "of" >> {
      "is None for an empty series" >> {
        PortfolioMetrics.of(Vector.empty) must beNone
      }

      "reports the dates of the first and last points" >> {
        val metrics = PortfolioMetrics.of(withDeposit)

        metrics.map(_.firstDate) must beSome(day1)
        metrics.map(_.lastDate) must beSome(day3)
      }

      "measures total return on the raw values, so a deposit counts as a gain" >> {
        PortfolioMetrics.of(withDeposit).map(_.totalReturn) must beSome(BigDecimal("0.65"))
      }

      "measures time-weighted return net of a deposit" >> {
        PortfolioMetrics.of(withDeposit).map(_.timeWeightedReturn) must beSome(BigDecimal("0.21"))
      }

      "measures max drawdown on the raw values, so a withdrawal counts as a fall" >> {
        PortfolioMetrics.of(withWithdrawal).map(_.maxDrawdown) must beSome(BigDecimal("0.4"))
      }
    }
  }
}
