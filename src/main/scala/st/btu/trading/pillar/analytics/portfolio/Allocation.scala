package st.btu.trading.pillar.analytics.portfolio

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** Cash is reported separately via `cashWeight`, never folded into the three breakdowns below. A
  * position with no weight contributes to none of them, since its share of total is unknown rather
  * than zero. A position of zero weight, such as a futures contract, adds nothing either, so a
  * type, currency or exchange that only such positions have is not a key.
  *
  * Each breakdown is keyed by the text of the field it groups by, `unknown` for a position without
  * that field; `byCurrency` is keyed by `Currency.value`.
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

  /** The JSON form a stored snapshot keeps its allocation in. Renaming a field breaks stored data.
    */
  implicit val codecForAllocation: Codec.AsObject[Allocation] = deriveCodec

  /** Groups `snapshot`'s priced positions into instrument-type, currency and exchange weight
    * breakdowns, each summing to at most `1 - cashWeight`. A position of zero weight is left out.
    */
  def of(snapshot: PositionSnapshot): Allocation = {
    val weighted = snapshot.positions.flatMap(p => p.weight.filter(_.signum != 0).map(p -> _))
    Allocation(
      byInstrumentType = grouped(weighted)(_.instrumentType),
      byCurrency = grouped(weighted)(_.currency.map(_.value)),
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
