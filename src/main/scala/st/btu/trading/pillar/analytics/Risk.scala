package st.btu.trading.pillar.analytics

import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import scala.math.BigDecimal.RoundingMode

/** Pure risk statistics over a per-period return series (e.g. `Returns.simple` output), plus
  * `maxDrawdown`, which runs over a value series directly. `periodsPerYear` annualizes a per-period
  * statistic.
  */
object Risk {

  /** Sample standard deviation of `returns`, annualized by `sqrt(periodsPerYear)`.
    *
    * @return
    *   `0` for fewer than two returns
    */
  def volatility(
    returns: Vector[BigDecimal],
    periodsPerYear: Int = TradingDaysPerYear
  ): BigDecimal =
    if (returns.size < 2) BigDecimal(0)
    else
      BigDecimal(sampleStdev(returns) * math.sqrt(periodsPerYear.toDouble))
        .setScale(ReportScale, RoundingMode.HALF_UP)

  /** Annualized Sharpe ratio: mean excess return over its standard deviation, scaled by
    * `sqrt(periodsPerYear)`, where excess is `returns` minus `riskFreePerPeriod`.
    *
    * @return
    *   `0` for fewer than two returns, or when the excess returns' standard deviation is 0
    */
  def sharpe(
    returns: Vector[BigDecimal],
    riskFreePerPeriod: BigDecimal = BigDecimal(0),
    periodsPerYear: Int = TradingDaysPerYear
  ): BigDecimal =
    if (returns.size < 2) BigDecimal(0)
    else {
      val excess = returns.map(_ - riskFreePerPeriod)
      val mean = divide(excess.sum, BigDecimal(excess.size))
      val stdev = sampleStdev(excess)
      if (stdev == 0.0) BigDecimal(0)
      else
        BigDecimal((mean.toDouble / stdev) * math.sqrt(periodsPerYear.toDouble))
          .setScale(ReportScale, RoundingMode.HALF_UP)
    }

  /** Like [[sharpe]], but the denominator is the downside deviation (root-mean-square of the
    * negative excess returns only, from the fixed target 0) instead of the full-sample standard
    * deviation.
    *
    * @return
    *   `0` for fewer than two returns, no negative excess return (no downside observed), or a zero
    *   downside deviation
    */
  def sortino(
    returns: Vector[BigDecimal],
    riskFreePerPeriod: BigDecimal = BigDecimal(0),
    periodsPerYear: Int = TradingDaysPerYear
  ): BigDecimal =
    if (returns.size < 2) BigDecimal(0)
    else {
      val excess = returns.map(_ - riskFreePerPeriod)
      val downside = excess.filter(_ < 0)
      if (downside.isEmpty) BigDecimal(0)
      else {
        val mean = divide(excess.sum, BigDecimal(excess.size))
        val downsideVariance = divide(downside.map(r => r * r).sum, BigDecimal(downside.size))
        val downsideDeviation = math.sqrt(downsideVariance.toDouble)
        if (downsideDeviation == 0.0) BigDecimal(0)
        else
          BigDecimal((mean.toDouble / downsideDeviation) * math.sqrt(periodsPerYear.toDouble))
            .setScale(ReportScale, RoundingMode.HALF_UP)
      }
    }

  /** Largest peak-to-trough fraction of `values`, as a positive number.
    *
    * @return
    *   `0` for an empty series
    */
  def maxDrawdown(values: Vector[BigDecimal]): BigDecimal = {
    final case class Acc(peak: BigDecimal, maxDd: BigDecimal)
    values
      .foldLeft(Acc(BigDecimal(0), BigDecimal(0))) { (acc, v) =>
        val peak = acc.peak.max(v)
        val dd = if (peak > 0) divide(peak - v, peak) else BigDecimal(0)
        Acc(peak, acc.maxDd.max(dd))
      }
      .maxDd
  }

  /** Historical (empirical) value at risk: the `(1 - confidence)` quantile of `returns` by nearest
    * rank, negated so a loss is reported as a positive number.
    *
    * @return
    *   `0` for an empty series
    */
  def valueAtRisk(returns: Vector[BigDecimal], confidence: BigDecimal): BigDecimal =
    if (returns.isEmpty) BigDecimal(0)
    else {
      val sorted = returns.sorted
      val tailProbability = BigDecimal(1) - confidence
      val index = (math.ceil((tailProbability * BigDecimal(sorted.size)).toDouble).toInt - 1)
        .max(0)
        .min(sorted.size - 1)
      -sorted(index)
    }

  /** OLS beta of `returns` against `benchmark`, over their aligned prefix of equal length (assumed
    * already period-aligned, not aligned by date here).
    *
    * @return
    *   `None` when the benchmark's variance is 0 (e.g. a flat benchmark), or fewer than two aligned
    *   pairs exist
    */
  def beta(returns: Vector[BigDecimal], benchmark: Vector[BigDecimal]): Option[BigDecimal] = {
    val n = returns.size.min(benchmark.size)
    if (n < 2) None
    else {
      val xs = returns.take(n)
      val ys = benchmark.take(n)
      val meanX = divide(xs.sum, BigDecimal(n))
      val meanY = divide(ys.sum, BigDecimal(n))
      val covariance =
        divide(xs.zip(ys).map { case (x, y) => (x - meanX) * (y - meanY) }.sum, BigDecimal(n - 1))
      val varianceY = divide(ys.map(y => (y - meanY) * (y - meanY)).sum, BigDecimal(n - 1))
      if (varianceY == BigDecimal(0)) None else Some(divide(covariance, varianceY))
    }
  }

  private def sampleStdev(xs: Vector[BigDecimal]): Double = {
    val mean = divide(xs.sum, BigDecimal(xs.size))
    val variance = divide(xs.map(x => (x - mean) * (x - mean)).sum, BigDecimal(xs.size - 1))
    math.sqrt(variance.toDouble)
  }
}
