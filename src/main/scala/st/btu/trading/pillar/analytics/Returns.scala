package st.btu.trading.pillar.analytics

import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import scala.math.BigDecimal.RoundingMode

/** Pure return-series calculations over a chronological series of values (prices, NAVs, equity
  * points, ...). Both functions need at least two values to produce a single return, returning an
  * empty series rather than raising when there are fewer. A pair whose previous value is not
  * positive is skipped rather than folded in, since its return would be undefined or sign-flipped.
  */
object Returns {

  /** Simple per-period returns: `v(t) / v(t-1) - 1` for each consecutive pair. */
  def simple(values: Vector[BigDecimal]): Vector[BigDecimal] =
    pairs(values).map { case (prev, curr) => divide(curr, prev) - 1 }

  /** Log (continuously compounded) per-period returns: `ln(v(t) / v(t-1))`, rounded to 8 decimal
    * places.
    */
  def log(values: Vector[BigDecimal]): Vector[BigDecimal] =
    pairs(values).map { case (prev, curr) =>
      BigDecimal(math.log(divide(curr, prev).toDouble)).setScale(ReportScale, RoundingMode.HALF_UP)
    }

  private def pairs(values: Vector[BigDecimal]): Vector[(BigDecimal, BigDecimal)] =
    if (values.size < 2) Vector.empty
    else values.zip(values.drop(1)).filter { case (prev, _) => prev > 0 }
}
