package st.btu.trading.pillar.analytics.portfolio

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import st.btu.trading.pillar.analytics.{DaysPerYear, Performance, Risk, Valuation}

import java.time.LocalDate

/** The performance and risk figures of a valuation series between its first date and its last.
  *
  * Returns, volatility and drawdown are fractions, not percentages: `0.05` is 5%. Every figure but
  * `totalReturn` takes the deposits and withdrawals out, so moving money in or out of the account
  * is neither performance nor risk.
  *
  * @param totalReturn
  *   the last value over the first, minus 1, so a deposit inside the series counts as a gain; `0`
  *   when the first value is not positive
  * @param timeWeightedReturn
  *   the daily returns with each day's deposit or withdrawal taken out, compounded
  * @param moneyWeightedReturn
  *   the annualized internal rate of return of the series' cash flows, which weighs when money was
  *   paid in or taken out; `None` when it cannot be solved, as for a single point
  * @param volatility
  *   the sample standard deviation of those daily returns, annualized over 365 days a year, since
  *   the series has a point for every calendar day
  * @param sharpe
  *   the mean of those daily returns over their standard deviation, at a zero risk-free rate and
  *   annualized the same way; a ratio, not a fraction
  * @param maxDrawdown
  *   the largest fall from a previous peak, as a positive fraction, of the value those daily
  *   returns compound into, so a withdrawal is not a fall
  */
final case class PortfolioMetrics(
  firstDate: LocalDate,
  lastDate: LocalDate,
  totalReturn: BigDecimal,
  timeWeightedReturn: BigDecimal,
  moneyWeightedReturn: Option[BigDecimal],
  volatility: BigDecimal,
  sharpe: BigDecimal,
  maxDrawdown: BigDecimal
)

object PortfolioMetrics {

  /** The JSON form a stored snapshot keeps a period's figures in. Renaming a field breaks stored
    * data.
    */
  implicit val codecForPortfolioMetrics: Codec.AsObject[PortfolioMetrics] = deriveCodec

  /** The figures of `points`, or `None` for an empty series. A single point gives zero for every
    * figure and no money-weighted return.
    *
    * @param points
    *   the valuation series, oldest first, one point per day, as `PortfolioValuationStorage.series`
    *   returns it; nothing here sorts it
    */
  def of(points: Vector[ValuationPoint]): Option[PortfolioMetrics] =
    for {
      first <- points.headOption
      last <- points.lastOption
    } yield {
      val series = points.map(p => Valuation(p.date, p.value, p.cashFlow))
      val returns = Performance.subPeriodReturns(series)
      PortfolioMetrics(
        firstDate = first.date,
        lastDate = last.date,
        totalReturn = Performance.totalReturn(first.value, last.value),
        timeWeightedReturn = Performance.timeWeighted(series),
        moneyWeightedReturn = Performance.moneyWeighted(series),
        volatility = Risk.volatility(returns, periodsPerYear = DaysPerYear),
        sharpe = Risk.sharpe(returns, periodsPerYear = DaysPerYear),
        maxDrawdown = Risk.maxDrawdown(growthIndex(returns))
      )
    }

  // What 1 at the first point grows to by each later point: the account's value with its deposits
  // and withdrawals taken out.
  private def growthIndex(returns: Vector[BigDecimal]): Vector[BigDecimal] =
    returns.scanLeft(BigDecimal(1))((index, r) => index * (1 + r))

  /** The figures of each [[Period]] that ends on `end`, over the `points` dated from
    * `period.from(end)` to `end`, both inclusive: the window `/performance` reads for that period.
    * A period whose window holds no point is absent.
    *
    * @param points
    *   the valuation series, oldest first, as for [[of]]; a point dated after `end` falls in no
    *   window
    */
  def byPeriod(points: Vector[ValuationPoint], end: LocalDate): Map[Period, PortfolioMetrics] =
    Period.values.flatMap { period =>
      val from = period.from(end)
      of(points.filter(p => !p.date.isBefore(from) && !p.date.isAfter(end))).map(period -> _)
    }.toMap
}
