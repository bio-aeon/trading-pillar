package st.btu.trading.pillar.models.market

import java.math.BigDecimal as JBigDecimal
import scala.math.BigDecimal.RoundingMode

// The price convention every adapter follows: a price is a BigDecimal at Scale decimal places,
// rounded half-up, whether it comes from Yahoo's Double quotes (fromDouble), Tinkoff's Quotation
// and MoneyValue units and nano pairs (fromUnitsNano) or Binance's decimal strings (fromDecimal).
// Eighteen places keep the digits of a coin priced far below a cent.
private[pillar] object Prices {

  val Scale = 18

  private val NanosPerUnit = 1000000000L

  def fromDouble(d: Double): BigDecimal =
    fromDecimal(BigDecimal(JBigDecimal.valueOf(d)))

  def fromUnitsNano(units: Long, nano: Int): BigDecimal =
    fromDecimal(BigDecimal(units) + BigDecimal(nano) / BigDecimal(NanosPerUnit))

  def fromDecimal(d: BigDecimal): BigDecimal =
    d.setScale(Scale, RoundingMode.HALF_UP)

  // The digits of v without trailing zeros or an exponent, so that a price shows as 0.00000823 and
  // as 150.25 whatever scale it carries.
  def plain(v: BigDecimal): String = v.bigDecimal.stripTrailingZeros.toPlainString
}
