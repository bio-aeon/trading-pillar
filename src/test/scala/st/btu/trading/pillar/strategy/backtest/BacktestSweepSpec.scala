package st.btu.trading.pillar.strategy.backtest

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.strategy.{ParameterizedStrategy, Sizing, StrategyId, StrategySignal}
import st.btu.trading.pillar.testkit.StrategyFakes
import st.btu.trading.pillar.testkit.StrategyFakes.ScriptedParameterizedStrategy

import java.time.LocalDate

class BacktestSweepSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")
  private val config = BacktestConfig(10000, 0, 0)

  private def risingBars(n: Int): List[Bar] =
    (0 until n).toList.map { i =>
      StrategyFakes.flatBar(
        ticker,
        LocalDate.of(2024, 1, 1).plusDays(i.toLong),
        BigDecimal(100 + i)
      )
    }

  // Buys AllIn on the very first bar and holds to the end - on a strictly rising price series this
  // always produces a positive totalReturn.
  private def buyAndHoldStrategy(q: String): ScriptedParameterizedStrategy =
    ScriptedParameterizedStrategy(
      id = StrategyId(s"scripted-$q"),
      warmupBars = 1,
      onBarFn = (ctx, _) =>
        if (ctx.history.size == 1) List(StrategySignal.Buy(ticker, Sizing.AllIn, "buy once"))
        else List(StrategySignal.Hold("hold")),
      withParametersFn = _ => Left("not used"),
      parameters = Map("q" -> q)
    )

  // Never trades - totalReturn is always exactly 0.
  private def neverTradeStrategy(q: String): ScriptedParameterizedStrategy =
    ScriptedParameterizedStrategy(
      id = StrategyId(s"scripted-$q"),
      warmupBars = 1,
      onBarFn = (_, _) => List(StrategySignal.Hold("never trades")),
      withParametersFn = _ => Left("not used"),
      parameters = Map("q" -> q)
    )

  // warmupBars = 5, so a grid point requesting this one fails BacktestEngine's own NotEnoughBars
  // check when run against fewer than 5 bars - used to prove that failure surfaces per point just
  // like withParameters' own InvalidStrategy does.
  private val needsWarmupStrategy: ScriptedParameterizedStrategy = ScriptedParameterizedStrategy(
    id = StrategyId("scripted-needs-warmup"),
    warmupBars = 5,
    onBarFn = (_, _) => List(StrategySignal.Hold("hold")),
    withParametersFn = _ => Left("not used")
  )

  // "q" -> "good"/"bad"/"needs-warmup" build the strategies above; anything else (including
  // "broken") fails withParameters - standing in for an invalid grid point (e.g. an unknown key or
  // a violated invariant on a real ParameterizedStrategy).
  private val base: ParameterizedStrategy = ScriptedParameterizedStrategy(
    id = StrategyId("base"),
    warmupBars = 1,
    onBarFn = (_, _) => Nil,
    withParametersFn = {
      case m if m.get("q").contains("good") => Right(buyAndHoldStrategy("good"))
      case m if m.get("q").contains("bad") => Right(neverTradeStrategy("bad"))
      case m if m.get("q").contains("needs-warmup") => Right(needsWarmupStrategy)
      case _ => Left("boom")
    }
  )

  "BacktestSweep" >> {
    "run" >> {

      "runs every grid point and turns a parameter-validation failure into an invalid-strategy error" >> {
        val grid = List(Map("q" -> "good"), Map("q" -> "bad"), Map("q" -> "broken"))
        for {
          points <- BacktestSweep.run[IO](base, grid, risingBars(10), config)
        } yield {
          points must haveSize(3)
          points.find(_.params("q") == "broken").map(_.result) must beSome(
            Left(BacktestError.InvalidStrategy("boom"))
          )
          points.find(_.params("q") == "good").map(_.result) must beSome.like { case r =>
            r must beRight[BacktestResult]
          }
          points.find(_.params("q") == "bad").map(_.result) must beSome.like { case r =>
            r must beRight[BacktestResult]
          }
        }
      }

      "surfaces the engine's own errors (e.g. not enough bars) per point, the same way" >> {
        val tooFewBars = risingBars(1)
        val grid = List(Map("q" -> "needs-warmup"))
        for {
          points <- BacktestSweep.run[IO](base, grid, tooFewBars, config, parallelism = 1)
        } yield points.head.result must beLeft(BacktestError.NotEnoughBars(5, 1))
      }
    }

    "rank" >> {

      "keeps only successful points, ranked by the given metric descending" >> {
        val grid = List(Map("q" -> "bad"), Map("q" -> "good"), Map("q" -> "broken"))
        for {
          points <- BacktestSweep.run[IO](base, grid, risingBars(10), config)
        } yield {
          val ranked = BacktestSweep.rank(points, _.totalReturn)
          ranked.map(_.params("q")) must_== List("good", "bad")
        }
      }

      "returns an empty list when every point failed" >> {
        val grid = List(Map("q" -> "broken"), Map("q" -> "also-broken"))
        for {
          points <- BacktestSweep.run[IO](base, grid, risingBars(10), config)
        } yield BacktestSweep.rank(points, _.totalReturn) must beEmpty
      }
    }
  }

  "Grid" >> {
    "expand" >> {

      "expands a single from/to/step range inclusive, rendering whole numbers without decimals" >> {
        Grid.expand(Map("fast" -> ((BigDecimal(10), BigDecimal(30), BigDecimal(10))))) must beRight(
          List(Map("fast" -> "10"), Map("fast" -> "20"), Map("fast" -> "30"))
        )
      }

      "renders a non-whole step with its decimal digits" >> {
        Grid.expand(
          Map(
            "proximityThreshold" -> ((BigDecimal("0.01"), BigDecimal("0.03"), BigDecimal("0.01")))
          )
        ) must beRight(
          List(
            Map("proximityThreshold" -> "0.01"),
            Map("proximityThreshold" -> "0.02"),
            Map("proximityThreshold" -> "0.03")
          )
        )
      }

      "cartesian-expands multiple keys, fast-major and slow-minor in alphabetical key order" >> {
        Grid.expand(
          Map(
            "fast" -> ((BigDecimal(10), BigDecimal(20), BigDecimal(10))),
            "slow" -> ((BigDecimal(50), BigDecimal(60), BigDecimal(10)))
          )
        ) must beRight(
          List(
            Map("fast" -> "10", "slow" -> "50"),
            Map("fast" -> "10", "slow" -> "60"),
            Map("fast" -> "20", "slow" -> "50"),
            Map("fast" -> "20", "slow" -> "60")
          )
        )
      }

      "allows exactly 100 combinations" >> {
        Grid
          .expand(Map("fast" -> ((BigDecimal(1), BigDecimal(100), BigDecimal(1)))))
          .map(_.size) must beRight(100)
      }

      "rejects a grid of more than 100 combinations" >> {
        Grid.expand(Map("fast" -> ((BigDecimal(1), BigDecimal(101), BigDecimal(1))))) must beLeft[
          String
        ]
      }

      "rejects a grid whose cartesian product exceeds 100 even if no single axis does" >> {
        Grid.expand(
          Map(
            "a" -> ((BigDecimal(1), BigDecimal(20), BigDecimal(1))),
            "b" -> ((BigDecimal(1), BigDecimal(10), BigDecimal(1)))
          )
        ) must beLeft[String]
      }

      "rejects a non-positive step" >> {
        Grid.expand(Map("fast" -> ((BigDecimal(10), BigDecimal(20), BigDecimal(0))))) must beLeft[
          String
        ]
      }

      "expands to a single combination when to equals from" >> {
        Grid.expand(Map("fast" -> ((BigDecimal(10), BigDecimal(10), BigDecimal(5))))) must beRight(
          List(Map("fast" -> "10"))
        )
      }
    }
  }
}
