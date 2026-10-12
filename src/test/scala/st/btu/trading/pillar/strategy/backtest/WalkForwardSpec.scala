package st.btu.trading.pillar.strategy.backtest

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.strategy.{Sizing, StrategyId, StrategySignal}
import st.btu.trading.pillar.testkit.StrategyFakes

import java.time.LocalDate

class WalkForwardSpec extends Specification {

  private val ticker = Ticker("AAPL")
  private val epoch = LocalDate.of(2024, 1, 1)

  private def bar(date: LocalDate, price: BigDecimal): Bar =
    StrategyFakes.flatBar(ticker, date, price)

  // One bar per calendar day starting at `epoch`, so bar counts and calendar-day counts coincide -
  // keeps the split math in these tests easy to hand-verify.
  private def dailyBars(n: Int, price: BigDecimal = 100): List[Bar] =
    (0 until n).toList.map(i => bar(epoch.plusDays(i.toLong), price))

  "WalkForward" >> {
    "split" >> {

      "rolls a 10-day series into 4-day train and 2-day test windows, rolled forward by the test length" >> {
        val windows = WalkForward.split(dailyBars(10), trainDays = 4, testDays = 2)

        windows must haveSize(3)
        windows.map(_.train.size) must_== List(4, 4, 4)
        windows.map(_.test.size) must_== List(2, 2, 2)
        windows(0).train.map(_.date) must_== (0 until 4).map(i => epoch.plusDays(i.toLong)).toList
        windows(0).test.map(_.date) must_== List(epoch.plusDays(4), epoch.plusDays(5))
        // window 1 rolls forward by testDays (2), not trainDays: train starts at day 2, not day 4.
        windows(1).train.map(_.date) must_== (2 until 6).map(i => epoch.plusDays(i.toLong)).toList
        windows(1).test.map(_.date) must_== List(epoch.plusDays(6), epoch.plusDays(7))
        // last window exactly exhausts the 10-day series.
        windows(2).test.map(_.date) must_== List(epoch.plusDays(8), epoch.plusDays(9))
      }

      "drops the last test window instead of truncating it when its calendar span isn't fully covered" >> {
        // Same trainDays/testDays as above: a 9-day series only has 1 of the 2 days the third
        // window's test period needs, so that window is dropped entirely rather than shrunk to 1 day.
        WalkForward.split(dailyBars(9), trainDays = 4, testDays = 2) must haveSize(2)
        WalkForward.split(dailyBars(10), trainDays = 4, testDays = 2) must haveSize(3)
      }

      "returns no windows when there isn't enough data for even one" >> {
        WalkForward.split(dailyBars(3), trainDays = 4, testDays = 2) must beEmpty
      }

      "returns no windows for a non-positive train or test length" >> {
        WalkForward.split(dailyBars(10), trainDays = 0, testDays = 2) must beEmpty
        WalkForward.split(dailyBars(10), trainDays = 4, testDays = 0) must beEmpty
      }

      "returns no windows for an empty series" >> {
        WalkForward.split(Nil, trainDays = 4, testDays = 2) must beEmpty
      }

      "does not depend on the input bars already being sorted" >> {
        val bars = dailyBars(10)
        WalkForward.split(scala.util.Random.shuffle(bars), 4, 2) must_== WalkForward.split(
          bars,
          4,
          2
        )
      }
    }

    "evaluate" >> {

      // Buys 10 shares on the very first bar it ever sees (which, with train prepended, is the
      // train window's first bar) and holds - so by the test window's start the position already
      // exists, entirely from warm-up history.
      val buyOnceStrategy = StrategyFakes.ScriptedStrategy(
        id = StrategyId("buy-once"),
        warmupBars = 1,
        onBarFn = (ctx, _) =>
          if (ctx.history.size == 1)
            List(StrategySignal.Buy(ticker, Sizing.Quantity(10), "buy once"))
          else List(StrategySignal.Hold("hold"))
      )

      "isolates the test window's own P&L: cash reset to the initial cash at the test start, trades before it dropped" >> {
        val train = (0 until 4).toList.map(i => bar(epoch.plusDays(i.toLong), 100))
        val test = List(bar(epoch.plusDays(4), 120), bar(epoch.plusDays(5), 130))
        val config = BacktestConfig(1000, 0, 0)

        val results = WalkForward.evaluate(buyOnceStrategy, List(Window(train, test)), config)

        results must haveSize(1)
        results.head must beRight[BacktestResult].like { case r =>
          r.dateFrom must_== epoch.plusDays(4)
          r.dateTo must_== epoch.plusDays(5)
          // the only real trade (the Buy) happened during train, i.e. before the test window.
          r.trades must beEmpty
          // raw equity at the test bars would have been 1200 then 1300 (10 shares bought for 1000
          // during train, marked at 120 then 130); rebased so the first test point reads
          // initialCash exactly, both points shift by the same constant delta (-200). Cash itself
          // was flat at 0 for both raw points (no trades in the test window), so it also lands on
          // the same -200 at both points - only the position's mark-to-market moves week to week,
          // which is why equity (not cash) carries the 1000 -> 1100 change.
          r.equityCurve.map(_.equity) must_== List(BigDecimal(1000), BigDecimal(1100))
          r.equityCurve.map(_.cash) must_== List(BigDecimal(-200), BigDecimal(-200))
          r.metrics.totalReturn must_== BigDecimal("0.1")
          r.metrics.finalEquity must_== BigDecimal(1100)
          r.metrics.maxDrawdown must_== BigDecimal(0)
          r.metrics.tradeCount must_== 0
          r.metrics.winRate must beNone
        }
      }

      "fails a window whose combined train and test bars don't satisfy the strategy's warmup requirement" >> {
        val needsTen = StrategyFakes.holdStrategy(warmupBars = 10)
        val window = Window(
          train = List(bar(epoch, 100), bar(epoch.plusDays(1), 100)),
          test = List(bar(epoch.plusDays(2), 100))
        )

        WalkForward.evaluate(needsTen, List(window), BacktestConfig(1000, 0, 0)) must_==
          List(Left(BacktestError.NotEnoughBars(10, 3)))
      }

      "fails a window with an empty test side instead of running the backtest at all" >> {
        val window = Window(train = List(bar(epoch, 100)), test = Nil)

        WalkForward.evaluate(
          StrategyFakes.holdStrategy(),
          List(window),
          BacktestConfig(1000, 0, 0)
        ) must_== List(Left(BacktestError.NoBars))
      }
    }

    "summary" >> {

      def resultWithReturn(totalReturn: BigDecimal): BacktestResult =
        BacktestResult(
          StrategyId("s"),
          ticker,
          Map.empty,
          epoch,
          epoch.plusDays(1),
          BacktestConfig(1000, 0, 0),
          Nil,
          Nil,
          BacktestMetrics(totalReturn, 0, 0, 0, None, 0, 0, 0, 0),
          Nil
        )

      "averages total return and the positive share over successful windows only, reporting the worst" >> {
        val results = List(
          Right(resultWithReturn(BigDecimal("0.30"))),
          Right(resultWithReturn(BigDecimal("-0.10"))),
          Right(resultWithReturn(BigDecimal("0.10"))),
          Right(resultWithReturn(BigDecimal("0.20"))),
          Left(BacktestError.NoBars)
        )

        val summary = WalkForward.summary(results)

        summary.windows must_== 5
        summary.meanTestReturn must_== BigDecimal("0.125")
        summary.worstTestReturn must_== BigDecimal("-0.10")
        summary.consistency must_== BigDecimal("0.75")
      }

      "reports zeros when every window failed" >> {
        val summary =
          WalkForward.summary(List(Left(BacktestError.NoBars), Left(BacktestError.NoBars)))

        summary.windows must_== 2
        summary.meanTestReturn must_== BigDecimal(0)
        summary.worstTestReturn must_== BigDecimal(0)
        summary.consistency must_== BigDecimal(0)
      }

      "reports zero windows for an empty result list" >> {
        WalkForward.summary(Nil) must_== WalkForwardSummary(0, 0, 0, 0)
      }
    }
  }
}
