package st.btu.trading.pillar.strategy.reference

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.strategy.StrategyId.value
import st.btu.trading.pillar.strategy.backtest.{BacktestConfig, BacktestEngine, BacktestResult}
import st.btu.trading.pillar.strategy.*
import st.btu.trading.pillar.testkit.StrategyFakes

import java.time.LocalDate

class MovingAverageCrossoverStrategySpec extends Specification {

  private val ticker = Ticker("AAPL")

  private def bar(i: Int, price: BigDecimal): Bar =
    StrategyFakes.flatBar(ticker, LocalDate.of(2024, 1, 1).plusDays(i.toLong), price)

  "MovingAverageCrossoverStrategy" >> {
    "onBar (via BacktestEngine)" >> {
      "emits exactly one buy at the fast/slow SMA crossover bar, on a downtrend-then-uptrend series" >> {
        // fast(2)/slow(3) SMA: crosses up exactly once, at index 6 (close=11)
        val closes = List(10, 9, 8, 7, 6, 8, 11, 14).map(BigDecimal(_))
        val bars = closes.zipWithIndex.map { case (c, i) => bar(i, c) }
        val strategy = new MovingAverageCrossoverStrategy(fast = 2, slow = 3)

        val result = BacktestEngine.run(strategy, bars, BacktestConfig(10000, 0, 0))

        result must beRight[BacktestResult].like { case r =>
          r.trades must haveSize(1)
          r.trades.head.side must_== Side.Buy
          // signal at index 6 (day 7) fills at index 7's (day 8) open
          r.trades.head.date must_== LocalDate.of(2024, 1, 1).plusDays(7)
        }
      }
    }

    "proximity" >> {
      val strategy = new MovingAverageCrossoverStrategy(fast = 2, slow = 3)
      val holding = Some(OpenPosition(ticker, 1L, BigDecimal(200), LocalDate.of(2024, 1, 1)))

      def warning(
        stored: List[Int],
        live: Int,
        position: Option[OpenPosition] = None
      ): Option[(Side, BigDecimal)] = {
        val bars = stored.zipWithIndex.map { case (c, i) => bar(i, BigDecimal(c)) }
        val ctx =
          StrategyContext(ticker, BarHistory(bars.toVector), position, PortfolioView(10000, 10000))
        strategy
          .proximity(ctx, bar(stored.size, BigDecimal(live)))
          .map(p => (p.side, p.distanceToTrigger))
      }

      // In the comments, SMA(2) and SMA(3) of the history, then with the live bar.
      "while flat" >> {
        "warns of a buy as the fast SMA climbs toward the slow one" >> {
          // 200 below 202, then 199.5 below 200: 0.25% apart.
          warning(List(206, 201, 199), live = 200) must_== Some((Side.Buy, BigDecimal("0.0025")))
        }

        "stays silent right after a sell, while the fast SMA falls away from the slow one" >> {
          // 199.5 below 199.67, then 198.5 below 199: within 1%, but further apart.
          warning(List(200, 200, 199), live = 198) must beNone
        }

        "stays silent once the fast SMA is above the slow one" >> {
          // 90 and 90, then 95 above 93.33.
          warning(List(90, 90, 90), live = 100) must beNone
        }

        "stays silent while the averages are further apart than the threshold" >> {
          // 200 below 203.33, then 195.5 below 198.67: nearer, but 1.6% apart.
          warning(List(210, 205, 195), live = 196) must beNone
        }
      }

      "while holding" >> {
        "warns of a sell as the fast SMA falls toward the slow one" >> {
          // 200 above 198, then 200.5 above 200: 0.25% apart.
          warning(List(194, 199, 201), live = 200, holding) must_==
            Some((Side.Sell, BigDecimal("0.0025")))
        }

        "stays silent right after a buy, while the fast SMA climbs away from the slow one" >> {
          // 200.5 above 200.33, then 201.5 above 201: within 1%, but further apart.
          warning(List(200, 200, 201), live = 202, holding) must beNone
        }
      }
    }

    "withParameters" >> {
      val strategy = new MovingAverageCrossoverStrategy(fast = 10, slow = 50)

      "overrides only the given keys and keeps the rest at their current value" >> {
        strategy.withParameters(Map("fast" -> "20")) must beRight[Strategy].like { case s =>
          s.id.value must_== "ma-cross-20-50"
          s.parameters must_== Map("fast" -> "20", "slow" -> "50")
        }
      }

      "overrides every key, including the proximity threshold" >> {
        val result =
          strategy.withParameters(
            Map("fast" -> "5", "slow" -> "25", "proximityThreshold" -> "0.02")
          )

        result must beRight[Strategy].like { case s => s.id.value must_== "ma-cross-5-25" }
      }

      "rejects an unknown parameter key" >> {
        strategy.withParameters(Map("bogus" -> "1")) must beLeft[String].like { case msg =>
          msg must contain("bogus")
        }
      }

      "rejects an unparsable integer value" >> {
        strategy.withParameters(Map("fast" -> "abc")) must beLeft[String].like { case msg =>
          msg must contain("fast")
        }
      }

      "rejects an unparsable decimal value" >> {
        strategy.withParameters(Map("proximityThreshold" -> "abc")) must beLeft[String].like {
          case msg => msg must contain("proximityThreshold")
        }
      }

      "rejects a combination where fast is not less than slow" >> {
        strategy.withParameters(Map("fast" -> "100")) must beLeft[String].like { case msg =>
          msg must contain("fast must be less than slow")
        }
      }

      "rejects fast=0 (violates the fast > 0 invariant)" >> {
        strategy.withParameters(Map("fast" -> "0", "slow" -> "10")) must beLeft[String]
      }
    }
  }
}
