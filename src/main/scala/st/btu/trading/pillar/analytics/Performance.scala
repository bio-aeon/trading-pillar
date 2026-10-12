package st.btu.trading.pillar.analytics

import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec
import scala.math.BigDecimal.RoundingMode

/** Pure return and performance calculations over a portfolio's valuation history. */
object Performance {

  private val XirrLowerBound = -0.99
  private val XirrUpperBound = 10.0
  private val XirrTolerance = 1e-8
  private val MaxBisectionIterations = 200

  /** `last / first - 1`.
    *
    * @return
    *   `0` when `first` is not positive, since there is no meaningful return to report
    */
  def totalReturn(first: BigDecimal, last: BigDecimal): BigDecimal =
    if (first <= 0) BigDecimal(0) else divide(last, first) - 1

  /** `total` compounded, or de-compounded, to a 365-day year.
    *
    * @return
    *   `total` unchanged when `calendarDays` is under 1, too short a span to annualize
    */
  def annualized(total: BigDecimal, calendarDays: Long): BigDecimal =
    if (calendarDays < 1) total
    else
      BigDecimal(math.pow((1 + total).toDouble, DaysPerYear.toDouble / calendarDays.toDouble) - 1)
        .setScale(ReportScale, RoundingMode.HALF_UP)

  /** Chains sub-period returns `value(t) / (value(t-1) + flow(t)) - 1`, so a deposit or withdrawal
    * on day `t` is added to the prior value before measuring that day's return (it is assumed to
    * land at the start of day `t`). `valuations` are consumed in the order given, not re-sorted.
    *
    * @return
    *   `0` for fewer than two valuations; a period whose denominator is not positive is skipped
    *   rather than folded in as an undefined return
    */
  def timeWeighted(valuations: Vector[Valuation]): BigDecimal = {
    val periodReturns = valuations.zip(valuations.drop(1)).flatMap { case (prev, curr) =>
      val base = prev.value + curr.externalFlow
      if (base > 0) Some(divide(curr.value, base) - 1) else None
    }
    periodReturns.foldLeft(BigDecimal(1))((acc, r) => acc * (1 + r)) - 1
  }

  /** The annualized XIRR of the valuation series' external cash flows, viewed from the investor's
    * side: paying in to open the position at the first date, each flow in between, and the value
    * received by closing the position out at the last date. Solved by bisecting net present value
    * over a wide rate range, years measured as ACT/365 from the first date.
    *
    * @return
    *   `None` for fewer than two valuations, or if the flows' net present value never changes sign
    *   across that range (nothing to solve for)
    */
  def moneyWeighted(valuations: Vector[Valuation]): Option[BigDecimal] =
    if (valuations.size < 2) None
    else {
      val first = valuations.head
      val last = valuations.last
      val middle = valuations.drop(1).dropRight(1)
      val flows: Vector[(LocalDate, BigDecimal)] =
        Vector((first.date, -first.value)) ++
          middle.map(v => (v.date, -v.externalFlow)) ++
          Vector((last.date, last.value))

      def npv(rate: Double): Double =
        flows.map { case (date, amount) =>
          val years = ChronoUnit.DAYS.between(first.date, date).toDouble / DaysPerYear.toDouble
          amount.toDouble / math.pow(1 + rate, years)
        }.sum

      bisect(npv, XirrLowerBound, XirrUpperBound, XirrTolerance)
        .map(rate => BigDecimal(rate).setScale(ReportScale, RoundingMode.HALF_UP))
    }

  // Bisection root-find of `f` over [lo, hi], to within `tolerance` (on the interval width, or an
  // exact zero found early). None if f(lo) and f(hi) don't straddle zero, so there's no root the
  // interval halving is guaranteed to converge to.
  private def bisect(
    f: Double => Double,
    lo: Double,
    hi: Double,
    tolerance: Double
  ): Option[Double] = {
    val fLo = f(lo)
    val fHi = f(hi)
    if (fLo == 0.0) Some(lo)
    else if (fHi == 0.0) Some(hi)
    else if ((fLo > 0) == (fHi > 0)) None
    else {
      val lowIsPositive = fLo > 0

      @tailrec
      def loop(low: Double, high: Double, iteration: Int): Double = {
        val mid = (low + high) / 2
        val fMid = f(mid)
        if (fMid == 0.0 || high - low < tolerance || iteration >= MaxBisectionIterations) mid
        else if ((fMid > 0) == lowIsPositive) loop(mid, high, iteration + 1)
        else loop(low, mid, iteration + 1)
      }

      Some(loop(lo, hi, 0))
    }
  }
}
