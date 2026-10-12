package st.btu.trading.pillar.models.market

import scala.math.BigDecimal.RoundingMode

// Shared price-scaling convention: every price this library stores or displays is a BigDecimal at
// Scale decimal places, rounded half-up, regardless of which adapter sourced it - Yahoo's own
// Double quotes (fromDouble) or Tinkoff's Quotation/MoneyValue units+nano pairs (fromUnitsNano).
private[pillar] object Prices {

  val Scale = 6

  private val NanosPerUnit = 1000000000L

  def fromDouble(d: Double): BigDecimal =
    BigDecimal(java.math.BigDecimal.valueOf(d)).setScale(Scale, RoundingMode.HALF_UP)

  def fromUnitsNano(units: Long, nano: Int): BigDecimal =
    (BigDecimal(units) + BigDecimal(nano) / BigDecimal(NanosPerUnit))
      .setScale(Scale, RoundingMode.HALF_UP)
}
