package st.btu.trading.pillar.gateways.screener

import enumeratum.values.{StringEnum, StringEnumEntry}
import st.btu.trading.pillar.models.market.Ticker

/** A predefined Yahoo Finance screen. */
sealed abstract class PredefinedScreen(val value: String) extends StringEnumEntry

object PredefinedScreen extends StringEnum[PredefinedScreen] {
  case object Gainers extends PredefinedScreen("gainers")
  case object Losers extends PredefinedScreen("losers")
  case object Active extends PredefinedScreen("active")

  val values = findValues
}

/** One row of a screener result.
  *
  * @param changePct
  *   already a percentage value (e.g. `3.5` means +3.5%), not a 0-1 fraction
  */
final case class ScreenerRow(
  ticker: Ticker,
  name: Option[String],
  price: Option[BigDecimal],
  changePct: Option[BigDecimal]
)

/** A market screener: the top rows matching a predefined screen. */
trait Screener[F[_]] {

  /** The top `count` rows of `screen`. */
  def predefined(screen: PredefinedScreen, count: Int): F[List[ScreenerRow]]
}
