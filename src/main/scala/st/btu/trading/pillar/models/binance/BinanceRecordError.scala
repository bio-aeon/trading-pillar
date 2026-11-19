package st.btu.trading.pillar.models.binance

/** Why a Binance record cannot be built or translated. */
sealed abstract class BinanceRecordError(message: String) extends Exception(message)

object BinanceRecordError {

  /** The record lacks a field the translation needs. The record has no id when the id is what is
    * missing.
    */
  final case class MissingField(
    source: BinanceSource,
    recordId: Option[BinanceRecordId],
    field: String
  ) extends BinanceRecordError(s"The ${source.value} record${described(recordId)} has no $field")

  /** The record carries a field whose value is not of the expected form. */
  final case class InvalidField(
    source: BinanceSource,
    recordId: Option[BinanceRecordId],
    field: String,
    value: String
  ) extends BinanceRecordError(
        s"The ${source.value} record${described(recordId)} has an invalid $field: '$value'"
      )

  /** A trade is on a pair whose assets are not known, so it cannot tell which asset was bought. */
  final case class UnknownPair(recordId: BinanceRecordId, symbol: BinanceSymbol)
      extends BinanceRecordError(
        s"The trade ${recordId.value} is on the pair ${symbol.value}, whose assets are not known"
      )

  private def described(recordId: Option[BinanceRecordId]): String =
    recordId.fold("")(id => s" ${id.value}")
}
