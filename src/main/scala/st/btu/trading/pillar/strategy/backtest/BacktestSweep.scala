package st.btu.trading.pillar.strategy.backtest

import cats.effect.{Async, Sync}
import cats.effect.syntax.concurrent.*
import st.btu.trading.pillar.models.market.Bar
import st.btu.trading.pillar.strategy.ParameterizedStrategy

/** One grid point's outcome: the parameter overrides it ran with, and either the resulting
  * `BacktestResult` or the error that stopped it.
  */
final case class SweepPoint(
  params: Map[String, String],
  result: Either[BacktestError, BacktestResult]
)

object BacktestSweep {

  /** Runs `base.withParameters(params)` and backtests the result for every point in `grid`, up to
    * `parallelism` at a time.
    *
    * @return
    *   one `SweepPoint` per grid point; a `withParameters` failure or a backtest failure becomes
    *   that point's own `Left` rather than failing the whole sweep
    */
  def run[F[_]: Async](
    base: ParameterizedStrategy,
    grid: List[Map[String, String]],
    bars: List[Bar],
    config: BacktestConfig,
    parallelism: Int = 4
  ): F[List[SweepPoint]] =
    grid.parTraverseN(parallelism.max(1)) { params =>
      Sync[F].delay(evaluate(base, params, bars, config))
    }

  /** The successful points only, ranked by `by(metrics)` descending. */
  def rank(points: List[SweepPoint], by: BacktestMetrics => BigDecimal): List[SweepPoint] =
    points
      .collect { case p @ SweepPoint(_, Right(result)) => p -> by(result.metrics) }
      .sortBy(_._2)(using Ordering[BigDecimal].reverse)
      .map(_._1)

  private def evaluate(
    base: ParameterizedStrategy,
    params: Map[String, String],
    bars: List[Bar],
    config: BacktestConfig
  ): SweepPoint =
    base.withParameters(params) match {
      case Left(msg) => SweepPoint(params, Left(BacktestError.InvalidStrategy(msg)))
      case Right(strategy) => SweepPoint(params, BacktestEngine.run(strategy, bars, config))
    }
}

/** Expands a parameter grid specification into the concrete combinations [[BacktestSweep.run]]
  * consumes: one `from:to:step` range per parameter key, cartesian product across keys.
  */
object Grid {

  // Combinations beyond this are rejected rather than silently truncated - a /sweep request this
  // large is almost certainly a typo (e.g. a step of 0.01 over a range meant in whole units) and
  // would otherwise make the command run for a long time.
  private val MaxCombinations = 100

  /** Expands `spec` (a parameter key to its inclusive `(from, to, step)` range) into every
    * combination, keys in alphabetical order so the result is deterministic regardless of the input
    * map's own iteration order. A value that is a whole number renders without a decimal point
    * (e.g. `"50"`, not `"50.0"`).
    *
    * @return
    *   a `Left` if any step is non-positive or the combination count exceeds 100
    */
  def expand(
    spec: Map[String, (BigDecimal, BigDecimal, BigDecimal)]
  ): Either[String, List[Map[String, String]]] =
    axisValues(spec).flatMap { axes =>
      val total = axes.foldLeft(1L) { case (acc, (_, values)) => acc * values.size }
      if (total > MaxCombinations)
        Left(s"Grid too large: $total combinations (max $MaxCombinations)")
      else Right(cartesian(axes))
    }

  private def axisValues(
    spec: Map[String, (BigDecimal, BigDecimal, BigDecimal)]
  ): Either[String, List[(String, List[String])]] =
    spec.keys.toList.sorted.foldLeft[Either[String, List[(String, List[String])]]](Right(Nil)) {
      (acc, key) =>
        acc.flatMap { axes =>
          val (from, to, step) = spec(key)
          if (step <= 0) Left(s"step for $key must be positive")
          else Right(axes :+ (key -> values(from, to, step).map(render)))
        }
    }

  private def values(from: BigDecimal, to: BigDecimal, step: BigDecimal): List[BigDecimal] =
    Iterator.iterate(from)(_ + step).takeWhile(_ <= to).take(Grid.MaxCombinations + 1).toList

  private def render(v: BigDecimal): String =
    if (v.isWhole) v.toBigInt.toString else v.toString

  private def cartesian(axes: List[(String, List[String])]): List[Map[String, String]] =
    axes.foldLeft(List(Map.empty[String, String])) { case (acc, (key, values)) =>
      for {
        combo <- acc
        value <- values
      } yield combo + (key -> value)
    }
}
