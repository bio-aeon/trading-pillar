package st.btu.trading.pillar.models.market

import enumeratum.values.{StringEnum, StringEnumEntry}

/** A broker or data provider a `MarketDataSource` can serve data from. */
sealed abstract class Source(val value: String) extends StringEnumEntry

object Source extends StringEnum[Source] {
  case object Yahoo extends Source("yahoo")
  case object Tinkoff extends Source("tinkoff")
  case object Binance extends Source("binance")

  val values = findValues
}
