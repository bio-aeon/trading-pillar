package st.btu.trading.pillar.alerts

import io.circe.generic.JsonCodec

/** How close the price must get to a [[PriceCrossed]] rule's threshold before a `PreTarget` alert
  * is raised. Stored alert rules hold it in the JSON form of its circe codec.
  */
@JsonCodec
sealed trait Proximity

object Proximity {

  /** Within `p` percent of the threshold, e.g. `p = 1` means within 1%. */
  final case class Percent(p: BigDecimal) extends Proximity

  /** Within `x`, an absolute price delta in the instrument's currency. */
  final case class Absolute(x: BigDecimal) extends Proximity

  /** Within `multiplier * ATR(period)`; the caller evaluating the rule computes the ATR value and
    * passes it into `AlertEngine.evaluatePriceRule`.
    */
  final case class Atr(multiplier: BigDecimal, period: Int) extends Proximity
}
