package st.btu.trading.pillar.strategy

import st.btu.trading.pillar.models.market.Bar
import st.btu.trading.pillar.strategy.indicators.Indicators

/** Bars accumulated so far, oldest first; always includes every bar up to and including the one
  * currently being evaluated.
  */
final class BarHistory(val bars: Vector[Bar]) {

  /** Number of bars held. */
  def size: Int = bars.size

  /** The most recent bar, or `None` if empty. */
  def last: Option[Bar] = bars.lastOption

  /** The last `n` bars, chronological; fewer than `n` when the history is shorter. */
  def lastN(n: Int): Vector[Bar] = bars.takeRight(n)

  /** Closing prices, oldest first. */
  def closes: Vector[BigDecimal] = bars.map(_.close)

  /** Simple moving average of the last `n` closes, or `None` if there are fewer than `n`. */
  def sma(n: Int): Option[BigDecimal] = Indicators.sma(closes, n)

  /** Exponential moving average of the last `n` closes, or `None` if there are fewer than `n`. */
  def ema(n: Int): Option[BigDecimal] = Indicators.ema(closes, n)

  /** Relative strength index over the last `n` closes, or `None` if there aren't enough. */
  def rsi(n: Int): Option[BigDecimal] = Indicators.rsi(closes, n)

  /** Average true range over the last `n` bars, or `None` if there aren't enough. */
  def atr(n: Int): Option[BigDecimal] = Indicators.atr(bars, n)
}

object BarHistory {

  /** The empty history. */
  val empty: BarHistory = new BarHistory(Vector.empty)

  /** Wraps `bars`, expected oldest first. */
  def apply(bars: Vector[Bar]): BarHistory = new BarHistory(bars)
}
