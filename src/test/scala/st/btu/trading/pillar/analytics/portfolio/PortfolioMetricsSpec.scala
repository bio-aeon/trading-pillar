package st.btu.trading.pillar.analytics.portfolio

import io.circe.parser.{decode, parse}
import io.circe.syntax.*
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

  // The value falls from 1000 to 600 only because 400 is withdrawn on day 3, so no money is lost:
  // with the withdrawal taken out, day 3 returns 600 / (1000 - 400) - 1 = 0.
  private val withWithdrawal = Vector(
    ValuationPoint(day1, 1000, 1000, 1000),
    ValuationPoint(day2, 1000, 1000, 0),
    ValuationPoint(day3, 600, 600, -400)
  )

  // Daily returns of 110 / 100 - 1 = 0.1 and 143 / 110 - 1 = 0.3, with no cash flow: mean 0.2,
  // sample variance ((0.1 - 0.2)^2 + (0.3 - 0.2)^2) / 1 = 0.02.
  private val noFlows = Vector(
    ValuationPoint(day1, 100, 100, 100),
    ValuationPoint(day2, 110, 100, 0),
    ValuationPoint(day3, 143, 100, 0)
  )

  // A series ending on endDate: a point on the day before the one-month window opens (2024-02-14),
  // one on its first day (2024-02-15), one on endDate, and an older one that only the longer
  // windows reach. A window's total return is its last value over its first.
  private val endDate = LocalDate.of(2024, 3, 15)
  private val spread = Vector(
    ValuationPoint(LocalDate.of(2024, 1, 15), 1000, 1000, 1000),
    ValuationPoint(LocalDate.of(2024, 2, 14), 1050, 1000, 0),
    ValuationPoint(LocalDate.of(2024, 2, 15), 1100, 1000, 0),
    ValuationPoint(endDate, 1210, 1000, 0)
  )

  private val sampleMetrics = PortfolioMetrics(
    firstDate = LocalDate.of(2026, 10, 1),
    lastDate = LocalDate.of(2026, 11, 1),
    totalReturn = BigDecimal("0.05"),
    timeWeightedReturn = BigDecimal("0.04"),
    moneyWeightedReturn = None,
    volatility = BigDecimal("0.12"),
    sharpe = BigDecimal("1.5"),
    maxDrawdown = BigDecimal("0.02")
  )

  private val sampleMetricsJson =
    """{
      |  "firstDate": "2026-10-01",
      |  "lastDate": "2026-11-01",
      |  "totalReturn": 0.05,
      |  "timeWeightedReturn": 0.04,
      |  "moneyWeightedReturn": null,
      |  "volatility": 0.12,
      |  "sharpe": 1.5,
      |  "maxDrawdown": 0.02
      |}""".stripMargin

  // A stored object that keeps `sampleMetricsJson` under each of `periodTokens`.
  private def storedByPeriod(periodTokens: String*): String =
    periodTokens.map(token => s""""$token": $sampleMetricsJson""").mkString("{", ",", "}")

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

      "does not count a withdrawal as a fall" >> {
        PortfolioMetrics.of(withWithdrawal).map(_.maxDrawdown) must beSome(BigDecimal(0))
      }

      "does not count a deposit as volatility" >> {
        // With the deposit taken out both daily returns are 10%, so they do not vary.
        PortfolioMetrics.of(withDeposit).map(_.volatility) must beSome(BigDecimal(0))
      }

      "annualizes volatility over 365 calendar days" >> {
        // sqrt(0.02) * sqrt(365) = sqrt(7.3), to 8 decimals; 252 trading days would give 2.24499443.
        PortfolioMetrics.of(noFlows).map(_.volatility) must beSome(BigDecimal("2.70185122"))
      }

      "annualizes the Sharpe ratio over 365 calendar days" >> {
        // 0.2 / sqrt(0.02) * sqrt(365) = sqrt(730), to 8 decimals; 252 days would give 22.44994432.
        PortfolioMetrics.of(noFlows).map(_.sharpe) must beSome(BigDecimal("27.01851217"))
      }

      "measures max drawdown on the value net of cash flows" >> {
        // Net of the 400 deposit, day 3 returns 1250 / (1100 + 400) - 1: the index runs 1, 1.1, then
        // 1.1 * 1250 / 1500 = 0.91666667, a fall of 1 - 1250 / 1500 = 1/6 from the peak of 1.1.
        val withLoss = Vector(
          ValuationPoint(day1, 1000, 1000, 1000),
          ValuationPoint(day2, 1100, 1000, 0),
          ValuationPoint(day3, 1250, 1400, 400)
        )

        PortfolioMetrics
          .of(withLoss)
          .map(_.maxDrawdown.setScale(8, BigDecimal.RoundingMode.HALF_UP)) must
          beSome(BigDecimal("0.16666667"))
      }
    }

    "byPeriod" >> {
      "measures each period over the points of its own window" >> {
        val totalReturns = PortfolioMetrics.byPeriod(spread, endDate).view.mapValues(_.totalReturn)

        // One month: 1100 -> 1210 is +10%. Every longer period starts at 1000: 1210 / 1000 - 1.
        totalReturns.toMap must_== Map(
          Period.OneMonth -> BigDecimal("0.1"),
          Period.ThreeMonths -> BigDecimal("0.21"),
          Period.SixMonths -> BigDecimal("0.21"),
          Period.OneYear -> BigDecimal("0.21"),
          Period.All -> BigDecimal("0.21")
        )
      }

      "opens a window on its first day, counting that day's point and not the day before's" >> {
        val oneMonth = PortfolioMetrics.byPeriod(spread, endDate)(Period.OneMonth)

        oneMonth.firstDate must_== LocalDate.of(2024, 2, 15)
      }

      "closes every window on the end date, leaving out a later point" >> {
        val later = spread :+ ValuationPoint(endDate.plusDays(1), 5000, 1000, 0)

        PortfolioMetrics.byPeriod(later, endDate).values.map(_.lastDate).toSet must_== Set(endDate)
      }

      "leaves out a period whose window holds no point" >> {
        val onlyOldPoint = Vector(ValuationPoint(LocalDate.of(2023, 1, 1), 1000, 1000, 1000))

        PortfolioMetrics.byPeriod(onlyOldPoint, endDate).keySet must_== Set(Period.All)
      }

      "is empty for an empty series" >> {
        PortfolioMetrics.byPeriod(Vector.empty, endDate) must beEmpty
      }
    }

    "JSON form" >> {
      "names the figures as stored, with null for a missing money-weighted return" >> {
        parse(sampleMetricsJson) must beRight(sampleMetrics.asJson)
      }

      "keys a map of periods by the tokens 1m, 3m, 6m, 1y and all" >> {
        val everyPeriod = Period.values.map(_ -> sampleMetrics).toMap

        parse(storedByPeriod("1m", "3m", "6m", "1y", "all")) must beRight(everyPeriod.asJson)
      }

      "reads a stored map back by its period tokens" >> {
        decode[Map[Period, PortfolioMetrics]](storedByPeriod("1m", "all")) must
          beRight(Map(Period.OneMonth -> sampleMetrics, Period.All -> sampleMetrics))
      }

      "rejects a key that is no period token" >> {
        decode[Map[Period, PortfolioMetrics]](storedByPeriod("2w")) must beLeft
      }
    }
  }
}
