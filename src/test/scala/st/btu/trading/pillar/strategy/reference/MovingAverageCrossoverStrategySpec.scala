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

      def ctxWithCloses(closes: List[BigDecimal]): StrategyContext = {
        val bars = closes.zipWithIndex.map { case (c, i) => bar(i, c) }
        StrategyContext(ticker, BarHistory(bars.toVector), None, PortfolioView(10000, 10000))
      }

      // The live bar is not yet in `ctx.history` (it is still forming) - proximity must fold its
      // close into both SMAs itself, per Strategy.proximity's own contract.
      "signals proximity when the fast/slow SMA distance, including the live bar, is inside the threshold" >> {
        // History closes 100, 100, 100; live bar close 99 (not yet in history).
        // SMA(2) over [100, 99] = 99.5; SMA(3) over [100, 100, 99] = 99.6667 -> distance ~0.167%,
        // inside the default 1% threshold. Ignoring the live bar (the pre-fix behavior) would
        // instead compare SMA(2) = SMA(3) = 100 over history alone - equal, not "fast < slow" -
        // and wrongly return None.
        val ctx = ctxWithCloses(List(BigDecimal(100), BigDecimal(100), BigDecimal(100)))
        val liveBar = bar(3, BigDecimal(99))

        val result = strategy.proximity(ctx, liveBar)

        result must beSome[PreSignal].like { case p => p.side must_== Side.Buy }
      }

      "returns nothing when the fast/slow SMA distance, including the live bar, is outside the threshold" >> {
        // History closes 100, 100, 100; live bar close 90 (not yet in history).
        // SMA(2) over [100, 90] = 95; SMA(3) over [100, 100, 90] = 96.667 -> distance ~1.72%,
        // outside the default 1% threshold.
        val ctx = ctxWithCloses(List(BigDecimal(100), BigDecimal(100), BigDecimal(100)))
        val liveBar = bar(3, BigDecimal(90))

        strategy.proximity(ctx, liveBar) must beNone
      }

      "returns nothing when there is no position and fast is already above slow (not approaching a buy)" >> {
        // History closes 90, 90, 90; live bar close 100 (not yet in history).
        // SMA(2) over [90, 100] = 95; SMA(3) over [90, 90, 100] = 93.333 -> fast > slow, so this
        // is not "approaching a buy" (nor is any position open, so not "approaching a sell" either).
        val ctx = ctxWithCloses(List(BigDecimal(90), BigDecimal(90), BigDecimal(90)))
        val liveBar = bar(3, BigDecimal(100))

        strategy.proximity(ctx, liveBar) must beNone
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
