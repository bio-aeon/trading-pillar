package st.btu.trading.pillar.strategy.reference

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.strategy.{Side, Strategy}
import st.btu.trading.pillar.strategy.backtest.{BacktestConfig, BacktestEngine, BacktestResult}
import st.btu.trading.pillar.testkit.StrategyFakes

import java.time.LocalDate

class RsiReversionStrategySpec extends Specification {

  private val ticker = Ticker("AAPL")

  private def bar(i: Int, price: BigDecimal): Bar =
    StrategyFakes.flatBar(ticker, LocalDate.of(2024, 1, 1).plusDays(i.toLong), price)

  "RsiReversionStrategy" >> {
    "onBar (via BacktestEngine)" >> {
      "buys when RSI crosses up through lower and sells when it later crosses down through upper" >> {
        // period=2 Wilder RSI on this series: RSI dips to 0 (index 2), then crosses up through 30
        // at index 3 (RSI=60, no position -> Buy), rises above 70 by index 5 (RSI~89.5), then
        // crosses back down through 70 at index 6 (RSI~25.4, holding a position -> Sell).
        val closes = List(100, 90, 80, 95, 110, 120, 90, 85).map(BigDecimal(_))
        val bars = closes.zipWithIndex.map { case (c, i) => bar(i, c) }
        val strategy = new RsiReversionStrategy(period = 2, lower = 30, upper = 70)

        val result = BacktestEngine.run(strategy, bars, BacktestConfig(10000, 0, 0))

        result must beRight[BacktestResult].like { case r =>
          r.trades must haveSize(2)
          r.trades.map(_.side) must_== List(Side.Buy, Side.Sell)
          // Buy signal at index 3 (day 4) fills at index 4 (day 5); Sell signal at index 6 (day 7)
          // fills at index 7 (day 8).
          r.trades.map(_.date) must_==
            List(LocalDate.of(2024, 1, 1).plusDays(4), LocalDate.of(2024, 1, 1).plusDays(7))
        }
      }
    }

    "withParameters" >> {
      val strategy = new RsiReversionStrategy(period = 14, lower = 30, upper = 70)

      "overrides only the given keys and keeps the rest at their current value" >> {
        strategy.withParameters(Map("period" -> "7")) must beRight[Strategy].like { case s =>
          s.id.value must_== "rsi-reversion-7-30-70"
          s.parameters must_== Map("period" -> "7", "lower" -> "30", "upper" -> "70")
        }
      }

      "overrides every key" >> {
        val result =
          strategy.withParameters(Map("period" -> "10", "lower" -> "20", "upper" -> "80"))

        result must beRight[Strategy].like { case s => s.id.value must_== "rsi-reversion-10-20-80" }
      }

      "rejects an unknown parameter key" >> {
        strategy.withParameters(Map("bogus" -> "1")) must beLeft[String].like { case msg =>
          msg must contain("bogus")
        }
      }

      "rejects an unparsable integer value" >> {
        strategy.withParameters(Map("period" -> "abc")) must beLeft[String].like { case msg =>
          msg must contain("period")
        }
      }

      "rejects an unparsable decimal value" >> {
        strategy.withParameters(Map("lower" -> "abc")) must beLeft[String].like { case msg =>
          msg must contain("lower")
        }
      }

      "rejects a combination where lower is not less than upper" >> {
        strategy.withParameters(Map("lower" -> "90")) must beLeft[String].like { case msg =>
          msg must contain("lower must be less than upper")
        }
      }
    }
  }
}
