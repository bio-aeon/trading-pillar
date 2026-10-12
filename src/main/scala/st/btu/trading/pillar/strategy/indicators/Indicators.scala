package st.btu.trading.pillar.strategy.indicators

import st.btu.trading.pillar.models.market.Bar

import java.math.MathContext

// Pure functions over price/bar series. None whenever there isn't enough history for the
// requested period. All indicators are recomputed from the start of the given series on every
// call, so callers passing the full accumulated history (as StrategyContext.history does) get
// values consistent with an incremental computation.
object Indicators {

  private val mc: MathContext = MathContext.DECIMAL64

  def divide(a: BigDecimal, b: BigDecimal): BigDecimal =
    BigDecimal(a.bigDecimal.divide(b.bigDecimal, mc))

  def sma(closes: Vector[BigDecimal], n: Int): Option[BigDecimal] =
    if (n <= 0 || closes.size < n) None
    else Some(divide(closes.takeRight(n).sum, BigDecimal(n)))

  // Seeded with the SMA of the first n closes, then smoothed forward over the rest of the series.
  def ema(closes: Vector[BigDecimal], n: Int): Option[BigDecimal] =
    if (n <= 0 || closes.size < n) None
    else {
      val alpha = divide(BigDecimal(2), BigDecimal(n + 1))
      val (seed, rest) = closes.splitAt(n)
      val seedEma = divide(seed.sum, BigDecimal(n))
      Some(rest.foldLeft(seedEma)((prev, close) => alpha * close + (BigDecimal(1) - alpha) * prev))
    }

  // Wilder's RSI: average gain/loss seeded as the simple average of the first n price changes,
  // then Wilder-smoothed forward over the rest of the series. Needs n+1 closes (n changes).
  def rsi(closes: Vector[BigDecimal], n: Int): Option[BigDecimal] =
    if (n <= 0 || closes.size < n + 1) None
    else {
      val changes = closes.zip(closes.drop(1)).map { case (prev, curr) => curr - prev }
      val gains = changes.map(c => if (c > 0) c else BigDecimal(0))
      val losses = changes.map(c => if (c < 0) -c else BigDecimal(0))

      val avgGain = wilderSmooth(gains, n)
      val avgLoss = wilderSmooth(losses, n)

      Some(
        if (avgLoss == BigDecimal(0)) BigDecimal(100)
        else {
          val rs = divide(avgGain, avgLoss)
          BigDecimal(100) - divide(BigDecimal(100), BigDecimal(1) + rs)
        }
      )
    }

  // Wilder's ATR: true range seeded as high-low for the first bar (no previous close), then
  // max(high-low, |high-prevClose|, |low-prevClose|); averaged over the first n true ranges,
  // Wilder-smoothed forward over the rest. Needs n bars.
  def atr(bars: Vector[Bar], n: Int): Option[BigDecimal] =
    if (n <= 0 || bars.size < n) None
    else Some(wilderSmooth(trueRanges(bars), n))

  private def trueRanges(bars: Vector[Bar]): Vector[BigDecimal] =
    bars.zipWithIndex.map {
      case (bar, 0) => bar.high - bar.low
      case (bar, idx) =>
        val prevClose = bars(idx - 1).close
        (bar.high - bar.low).max((bar.high - prevClose).abs).max((bar.low - prevClose).abs)
    }

  // Simple average of the first n values, Wilder-smoothed (avg_t = (avg_(t-1) * (n-1) + x_t) / n)
  // forward over the rest.
  private def wilderSmooth(values: Vector[BigDecimal], n: Int): BigDecimal = {
    val (seed, rest) = values.splitAt(n)
    val seedAvg = divide(seed.sum, BigDecimal(n))
    rest.foldLeft(seedAvg)((prev, x) => divide(prev * BigDecimal(n - 1) + x, BigDecimal(n)))
  }
}
