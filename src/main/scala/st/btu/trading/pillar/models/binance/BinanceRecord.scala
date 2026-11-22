package st.btu.trading.pillar.models.binance

import io.circe.JsonObject
import st.btu.trading.pillar.models.domain.{AccountId, OperationRef}

import java.time.Instant

/** One record of Binance's account history as Binance reported it, kept so that the translation
  * into operations can be done again without reading the record again.
  *
  * @param accountId
  *   the account the record is stored under
  * @param source
  *   the endpoint the record was read from
  * @param recordId
  *   the record's id within its source
  * @param occurredAt
  *   when Binance dates the record
  * @param attributes
  *   the record's fields exactly as Binance sent them
  */
final case class BinanceRecord(
  accountId: AccountId,
  source: BinanceSource,
  recordId: BinanceRecordId,
  occurredAt: Instant,
  attributes: JsonObject
) {

  /** The `ref` of the record's own operation: its source and its record id, such as
    * `trade/BTCUSDC/28457`. It is unique within the account, since a source's record ids are.
    */
  def ref: OperationRef =
    OperationRef(s"${source.value}${BinanceRecord.RefSeparator}${recordId.value}")

  /** The `ref` of an operation of the record beyond its own, told apart by `suffix`: the record's
    * `ref`, a slash and the suffix, such as `trade/BTCUSDC/28457/fee`.
    */
  def refWith(suffix: String): OperationRef =
    OperationRef(s"${ref.value}${BinanceRecord.RefSeparator}$suffix")

  /** Whether `operationRef` belongs to an operation of this record: it is the record's `ref`, or
    * the record's `ref` followed by a slash and a suffix. The record's `trade/BTCUSDC/28` owns
    * `trade/BTCUSDC/28` and `trade/BTCUSDC/28/fee`, and not `trade/BTCUSDC/281`.
    */
  def owns(operationRef: OperationRef): Boolean = {
    val own = ref.value
    val candidate = operationRef.value
    candidate == own || candidate.startsWith(own + BinanceRecord.RefSeparator)
  }

  /** The pair a trade is on, `None` for a record of any other source. */
  def tradeSymbol: Option[BinanceSymbol] =
    if (source == BinanceSource.Trade) fields.text(BinanceRecord.Field.Symbol).map(BinanceSymbol(_))
    else None

  private[pillar] def fields: BinanceFields = new BinanceFields(source, Some(recordId), attributes)
}

object BinanceRecord {

  private[pillar] val RefSeparator = "/"

  // The fields that name a record and date it, as Binance names them.
  private object Field {
    val Symbol = "symbol"
    val Id = "id"
    val OrderNo = "orderNo"
    val OrderId = "orderId"
    val TransId = "transId"
    val Time = "time"
    val InsertTime = "insertTime"
    val ApplyTime = "applyTime"
    val CreateTime = "createTime"
    val DivTime = "divTime"
    val OperateTime = "operateTime"
  }

  /** The record that `attributes`, an object Binance sent for `source`, describes: a trade's id is
    * its symbol and its trade id, a deposit's, a withdrawal's or a distribution's its `id`, a fiat
    * order's or a fiat payment's its `orderNo`, a Convert's the digits of its `orderId`, which
    * exceeds what a double holds, and a dust conversion's its own `transId`, not that of its
    * details. Its time is the `time` of a trade, the `insertTime` of a deposit, the `applyTime` of
    * a withdrawal, a UTC date and time, the `divTime` of a distribution, the `operateTime` of a
    * dust conversion, and the `createTime` of the others.
    *
    * @return
    *   the record, or the field it lacks or holds in the wrong form
    */
  def of(
    accountId: AccountId,
    source: BinanceSource,
    attributes: JsonObject
  ): Either[BinanceRecordError, BinanceRecord] = {
    val unnamed = new BinanceFields(source, None, attributes)
    for {
      id <- recordIdOf(source, unnamed)
      named = new BinanceFields(source, Some(id), attributes)
      at <- occurredAtOf(source, named)
    } yield BinanceRecord(accountId, source, id, at, attributes)
  }

  /** The id Binance gave a trade, as `attributes`, a trade, carries it. */
  def tradeIdOf(attributes: JsonObject): Option[BinanceTradeId] =
    new BinanceFields(BinanceSource.Trade, None, attributes)
      .text(Field.Id)
      .flatMap(_.toLongOption)
      .map(BinanceTradeId(_))

  private def recordIdOf(
    source: BinanceSource,
    fields: BinanceFields
  ): Either[BinanceRecordError, BinanceRecordId] =
    source match {
      case BinanceSource.Trade =>
        for {
          symbol <- fields.required(Field.Symbol)
          id <- fields.required(Field.Id)
        } yield BinanceRecordId(s"$symbol$RefSeparator$id")
      case BinanceSource.Deposit | BinanceSource.Withdrawal | BinanceSource.Dividend =>
        fields.required(Field.Id).map(BinanceRecordId(_))
      case BinanceSource.FiatDeposit | BinanceSource.FiatWithdrawal | BinanceSource.FiatBuy |
          BinanceSource.FiatSell =>
        fields.required(Field.OrderNo).map(BinanceRecordId(_))
      case BinanceSource.Convert => fields.required(Field.OrderId).map(BinanceRecordId(_))
      case BinanceSource.Dust => fields.required(Field.TransId).map(BinanceRecordId(_))
    }

  private def occurredAtOf(
    source: BinanceSource,
    fields: BinanceFields
  ): Either[BinanceRecordError, Instant] =
    source match {
      case BinanceSource.Trade => fields.requiredEpochMillis(Field.Time)
      case BinanceSource.Deposit => fields.requiredEpochMillis(Field.InsertTime)
      case BinanceSource.Withdrawal => fields.requiredUtcDateTime(Field.ApplyTime)
      case BinanceSource.Dividend => fields.requiredEpochMillis(Field.DivTime)
      case BinanceSource.Dust => fields.requiredEpochMillis(Field.OperateTime)
      case BinanceSource.FiatDeposit | BinanceSource.FiatWithdrawal | BinanceSource.FiatBuy |
          BinanceSource.FiatSell | BinanceSource.Convert =>
        fields.requiredEpochMillis(Field.CreateTime)
    }
}
