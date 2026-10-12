package st.btu.trading.pillar.analytics.portfolio

/** Cash is reported separately via `cashWeight`, never folded into the three breakdowns below. A
  * position with no weight contributes to none of them, since its share of total is unknown rather
  * than zero.
  */
final case class Allocation(
  byInstrumentType: Map[String, BigDecimal],
  byCurrency: Map[String, BigDecimal],
  byExchange: Map[String, BigDecimal],
  cashWeight: BigDecimal,
  unpricedWeightUnknown: Int
)

object Allocation {

  private val Zero = BigDecimal(0)
  private val Unknown = "unknown"

  /** Groups `snapshot`'s priced positions into instrument-type, currency and exchange weight
    * breakdowns, each summing to at most `1 - cashWeight`.
    */
  def of(snapshot: PositionSnapshot): Allocation = {
    val weighted = snapshot.positions.flatMap(p => p.weight.map(p -> _))
    Allocation(
      byInstrumentType = grouped(weighted)(_.instrumentType),
      byCurrency = grouped(weighted)(_.currency),
      byExchange = grouped(weighted)(_.exchange),
      cashWeight = if (snapshot.total == Zero) Zero else snapshot.cash / snapshot.total,
      unpricedWeightUnknown = snapshot.unpriced
    )
  }

  private def grouped(
    weighted: Vector[(PositionValue, BigDecimal)]
  )(key: PositionValue => Option[String]): Map[String, BigDecimal] =
    weighted
      .groupMapReduce { case (position, _) => key(position).getOrElse(Unknown) } { case (_, w) =>
        w
      }(_ + _)
}
