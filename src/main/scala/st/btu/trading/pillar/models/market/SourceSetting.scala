package st.btu.trading.pillar.models.market

/** Where one ticker's market data comes from: a [[Source]] plus what that source needs to pick the
  * instrument.
  */
sealed trait SourceSetting {

  /** The provider this setting selects. */
  def source: Source
}

object SourceSetting {

  /** Yahoo Finance, which looks the instrument up as `symbol`, or as the ticker itself when `None`.
    */
  final case class Yahoo(symbol: Option[YahooSymbol]) extends SourceSetting {
    def source: Source = Source.Yahoo
  }

  /** Tinkoff Invest, on the board with `classCode`, or on the adapter's default board when `None`.
    */
  final case class Tinkoff(classCode: Option[ClassCode]) extends SourceSetting {
    def source: Source = Source.Tinkoff
  }
}
