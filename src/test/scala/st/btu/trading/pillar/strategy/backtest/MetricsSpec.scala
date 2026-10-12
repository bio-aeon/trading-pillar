package st.btu.trading.pillar.strategy.backtest

import org.specs2.mutable.Specification
import st.btu.trading.pillar.strategy.Side

import java.time.LocalDate

class MetricsSpec extends Specification {

  private val d1 = LocalDate.of(2024, 1, 1)
  private val d2 = LocalDate.of(2024, 1, 2)
  private val d3 = LocalDate.of(2024, 1, 3)
  private val d4 = LocalDate.of(2024, 1, 4)

  private def point(date: LocalDate, equity: BigDecimal): EquityPoint =
    EquityPoint(date, equity, equity, 0L)

  "Metrics" >> {
    "compute" >> {

      "computes total return and max drawdown from a known equity path" >> {
        val curve = List(point(d1, 1000), point(d2, 1200), point(d3, 900), point(d4, 1100))
        val metrics = Metrics.compute(1000, curve, Nil, 50, 55)

        metrics.totalReturn must_== BigDecimal(0.1)
        metrics.maxDrawdown must_== BigDecimal(0.25)
        metrics.finalEquity must_== BigDecimal(1100)
        metrics.buyAndHoldReturn must_== BigDecimal(0.1)
      }

      "reports a Sharpe of 0 for a flat equity series" >> {
        val curve = List(point(d1, 1000), point(d2, 1000), point(d3, 1000))
        val metrics = Metrics.compute(1000, curve, Nil, 50, 50)

        metrics.sharpeAnnualized must_== BigDecimal(0)
      }

      "reports a Sharpe of 0 when there are fewer than 2 returns" >> {
        val curve = List(point(d1, 1000))
        val metrics = Metrics.compute(1000, curve, Nil, 50, 50)

        metrics.sharpeAnnualized must_== BigDecimal(0)
      }

      "reports a Sharpe of 0 when an equity point is exactly 0, since the pair right after it is skipped" >> {
        // equity 1000 -> 0 -> 500: the first pair returns -1 (0/1000 - 1); the second pair's
        // previous value is 0, so Returns.simple skips it rather than dividing by it, leaving only
        // one return - too few for Risk.sharpe to compute anything but 0.
        val curve = List(point(d1, 1000), point(d2, 0), point(d3, 500))
        val metrics = Metrics.compute(1000, curve, Nil, 50, 50)

        metrics.sharpeAnnualized must_== BigDecimal(0)
      }

      "reports no win rate when there are no round trips" >> {
        val curve = List(point(d1, 1000))
        val metrics = Metrics.compute(1000, curve, Nil, 50, 50)

        metrics.winRate must beNone
        metrics.roundTrips must_== 0
      }

      "computes a win rate from completed round trips (quantity returning to 0)" >> {
        val curve = List(point(d1, 1000), point(d4, 1100))
        val trades = List(
          Trade(d1, Side.Buy, 10, 100, 0, "open 1"),
          Trade(d2, Side.Sell, 10, 110, 0, "close 1 (win)"),
          Trade(d3, Side.Buy, 10, 100, 0, "open 2"),
          Trade(d4, Side.Sell, 10, 90, 0, "close 2 (loss)")
        )
        val metrics = Metrics.compute(1000, curve, trades, 50, 50)

        metrics.roundTrips must_== 2
        metrics.winRate must beSome(BigDecimal(0.5))
        metrics.tradeCount must_== 4
      }
    }
  }
}
