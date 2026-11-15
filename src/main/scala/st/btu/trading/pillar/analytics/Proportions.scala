package st.btu.trading.pillar.analytics

import st.btu.trading.pillar.strategy.indicators.Indicators.divide

private[pillar] object Proportions {

  // `total` shared in proportion to `weights`, which add up to more than zero. Each share but the
  // last is rounded, and the last is what the others leave, so the shares add up to `total` exactly
  // however the division rounds.
  def apportion(total: BigDecimal, weights: List[BigDecimal]): List[BigDecimal] =
    if (weights.isEmpty) Nil
    else {
      val sum = weights.sum
      val leading = weights.init.map(weight => divide(total * weight, sum))
      leading :+ (total - leading.sum)
    }
}
