package st.btu.trading.pillar.analytics.portfolio

import java.time.ZonedDateTime

/** An account's portfolio as of `holdings.date`: its holdings, their allocation, and the
  * performance figures of each period that ends on that date.
  *
  * @param metrics
  *   the figures of each [[Period]] whose window holds a valuation point, as
  *   [[PortfolioMetrics.byPeriod]] gives them; a period with no point is absent
  * @param computedAt
  *   when the snapshot was computed, in UTC
  */
final case class PortfolioSnapshot(
  holdings: PositionSnapshot,
  metrics: Map[Period, PortfolioMetrics],
  computedAt: ZonedDateTime
) {

  /** The weights of `holdings` by instrument type, currency and exchange, and its cash weight. */
  def allocation: Allocation = Allocation.of(holdings)
}
