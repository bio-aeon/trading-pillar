package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.strategy.indicators.Indicators.divide

// An amount of money in each of the currencies it was paid in. A currency with a zero amount is
// not in it, so a cost of nothing is the empty map, and every operation here keeps it so.
private[tax] object Costs {

  private val Zero = BigDecimal(0)

  val Free: Map[Currency, BigDecimal] = Map.empty

  def in(currency: Currency, amount: BigDecimal): Map[Currency, BigDecimal] =
    if (amount.signum == 0) Free else Map(currency -> amount)

  def total(costs: Iterable[Map[Currency, BigDecimal]]): Map[Currency, BigDecimal] =
    costs.foldLeft(Free)(_.plus(_))

  extension (cost: Map[Currency, BigDecimal]) {

    def plus(other: Map[Currency, BigDecimal]): Map[Currency, BigDecimal] =
      other
        .foldLeft(cost) { case (sum, (currency, amount)) =>
          sum.updated(currency, sum.getOrElse(currency, Zero) + amount)
        }
        .filterNot { case (_, amount) => amount.signum == 0 }

    def times(factor: BigDecimal): Map[Currency, BigDecimal] =
      scaled(_ * factor)

    // At the precision of `Indicators.divide`, which the lots a corporate action makes are divided
    // with.
    def over(divisor: BigDecimal): Map[Currency, BigDecimal] =
      scaled(divide(_, divisor))

    // With BigDecimal's own division, which derives the unit cost of a purchase from what it paid;
    // nothing when `divisor` is zero.
    def perUnit(divisor: BigDecimal): Map[Currency, BigDecimal] =
      if (divisor.signum == 0) Free else scaled(_ / divisor)

    private def scaled(change: BigDecimal => BigDecimal): Map[Currency, BigDecimal] =
      cost.flatMap { case (currency, amount) =>
        Some(change(amount)).filter(_.signum != 0).map(currency -> _)
      }
  }
}
