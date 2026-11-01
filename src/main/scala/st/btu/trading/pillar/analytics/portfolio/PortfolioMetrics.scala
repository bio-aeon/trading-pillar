package st.btu.trading.pillar.analytics.portfolio

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import st.btu.trading.pillar.analytics.{Performance, Returns, Risk, Valuation}

import java.time.LocalDate

/** The performance and risk figures of a valuation series between its first date and its last.
  *
  * Returns, volatility and drawdown are fractions, not percentages: `0.05` is 5%.
  *
  * @param totalReturn
  *   the last value over the first, minus 1, so a deposit inside the series counts as a gain; `0`
  *   when the first value is not positive
  * @param timeWeightedReturn
  *   the return with each day's deposit or withdrawal taken out, so cash flows do not count as
  *   performance
  * @param moneyWeightedReturn
  *   the annualized internal rate of return of the series' cash flows, which weighs when money was
  *   paid in or taken out; `None` when it cannot be solved, as for a single point
  * @param volatility
  *   the annualized sample standard deviation of the per-point returns
  * @param sharpe
  *   the annualized ratio of the mean per-point return to its standard deviation, at a zero
  *   risk-free rate; a ratio, not a fraction
  * @param maxDrawdown
  *   the largest fall of the value from a previous peak, as a positive fraction
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
      // Volatility/Sharpe/max drawdown run on the raw value series' simple returns, not
      // TWR-adjusted ones - the same convention strategy.backtest.Metrics uses for an equity curve
      // (see docs/analytics.md); TWR/MWR are the ones that account for cash flows exactly.
      val series = points.map(p => Valuation(p.date, p.value, p.cashFlow))
      val values = points.map(_.value)
      val returns = Returns.simple(values)
      PortfolioMetrics(
        firstDate = first.date,
        lastDate = last.date,
        totalReturn = Performance.totalReturn(first.value, last.value),
        timeWeightedReturn = Performance.timeWeighted(series),
        moneyWeightedReturn = Performance.moneyWeighted(series),
        volatility = Risk.volatility(returns),
        sharpe = Risk.sharpe(returns),
        maxDrawdown = Risk.maxDrawdown(values)
      )
    }

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
