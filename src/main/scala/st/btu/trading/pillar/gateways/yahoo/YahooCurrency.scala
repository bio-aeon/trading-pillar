package st.btu.trading.pillar.gateways.yahoo

import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.Prices

import scala.math.BigDecimal.RoundingMode

// A currency code as Yahoo states it: a major currency, or a minor unit, a hundredth of one.
private[yahoo] sealed trait YahooCurrency {

  // The currency this library reports amounts stated in this one in.
  def reported: Currency

  // `amount` as Yahoo states it, in the reported currency and at the price scale.
  def toReported(amount: Double): BigDecimal
}

private[yahoo] object YahooCurrency {

  private val MinorUnitsPerMajor = BigDecimal(100)

  // Yahoo marks a minor unit by the case of the code: pence, cents and agorot.
  private val MajorOfMinorUnit: Map[String, Currency] =
    Map("GBp" -> Currency("GBP"), "ZAc" -> Currency("ZAR"), "ILA" -> Currency("ILS"))

  final case class Major(currency: Currency) extends YahooCurrency {
    def reported: Currency = currency

    def toReported(amount: Double): BigDecimal = Prices.fromDouble(amount)
  }

  final case class Minor(major: Currency) extends YahooCurrency {
    def reported: Currency = major

    def toReported(amount: Double): BigDecimal =
      (Prices.fromDouble(amount) / MinorUnitsPerMajor).setScale(Prices.Scale, RoundingMode.HALF_UP)
  }

  def of(code: String): YahooCurrency =
    MajorOfMinorUnit.get(code).fold[YahooCurrency](Major(Currency(code)))(Minor(_))
}
