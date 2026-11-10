package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.ibkr.IbkrAttributes.{Category, Name}

import java.time.{Instant, ZoneId}
import java.util.Locale

/** Translates the rows of an Activity Flex Query statement into `AccountOperation`s. Pure: the
  * translation of a row depends on the row, the time zone IB's times are in, and which trades have
  * been cancelled.
  *
  * One row gives one or more operations. The `ref` of the first is the row's own, its section and
  * transaction id (`trade/123456`), and the others add a suffix (`trade/123456/commission`), so a
  * `ref` is unique by construction and names the row it comes from:
  *   - a stock trade is a buy or a sell, with its commission and its transaction tax as fees of
  *     their own;
  *   - a currency conversion is two `exchange` operations, one per currency, with the commission as
  *     a fee;
  *   - a trade of any other asset category is `other`, with its cash and no position;
  *   - a cash transaction is classified by IB's type of it;
  *   - a transfer of stock is `security_in` or `security_out`, and a leg of a corporate action is
  *     `security_in` or `security_out` by the sign of its quantity.
  *
  * An operation is executed, except those of a trade that was cancelled and of the cancelling row,
  * which are cancelled. A shape the translation does not know is `other`, carrying the cash and no
  * position.
  */
object IbkrRowTranslation {

  private type Translation[A] = Either[IbkrStatementError, A]

  private val Zero = BigDecimal(0)

  // The attributes that date a row, in the order they are tried. The report date dates a row that
  // carries none of them.
  private val OccurrenceAttributes = List(Name.DateTime, Name.Date, Name.TradeDate)

  // A currency conversion is a trade of a pair such as EUR.USD.
  private val CurrencyPair = "([A-Z]{3})\\.([A-Z]{3})".r

  private val CancelTransactionTypes = Set("TradeCancel", "FracShareCancel")
  private val CancelNote = "Ca"
  private val NoteSeparator = ";"

  // What tells the operations of a row beyond its first from it, in their `ref`s; the two legs of a
  // currency conversion are told apart by the currency.
  private val CommissionSuffix = "commission"
  private val TaxesSuffix = "taxes"
  private val CashSuffix = "cash"

  private val ExchangeType = "Currency exchange"
  private val CommissionType = "Commission"
  private val TaxType = "Transaction tax"

  private val InDirection = "IN"
  private val OutDirection = "OUT"

  private val DepositsAndWithdrawals = Set("deposits/withdrawals", "deposits & withdrawals")

  // IB's types of cash transaction other than deposits and withdrawals, lower-cased, by the kind
  // they are translated to. A type not in this list is `other`.
  private val CashKinds: Map[String, OperationKind] = Map(
    "dividends" -> OperationKind.Dividend,
    "payment in lieu of dividends" -> OperationKind.Dividend,
    "withholding tax" -> OperationKind.Tax,
    "broker interest received" -> OperationKind.Interest,
    "broker interest paid" -> OperationKind.Interest,
    "bond interest received" -> OperationKind.Coupon,
    "bond interest paid" -> OperationKind.Coupon,
    "other fees" -> OperationKind.Fee,
    "advisor fees" -> OperationKind.Fee,
    "commission adjustments" -> OperationKind.Fee
  )

  /** The operations `row` translates to.
    *
    * @param zone
    *   the time zone of the times in the statement, which carry none
    * @param canceled
    *   the ids of the trades that cancelling rows name; the operations of such a trade are
    *   cancelled too
    * @return
    *   the operations, or the reason the row cannot be translated: an attribute it needs is missing
    *   or malformed
    */
  def translate(
    row: IbkrStatementRow,
    zone: ZoneId,
    canceled: Set[IbkrTransactionId]
  ): Either[IbkrStatementError, List[AccountOperation]] = {
    val fields = new Fields(row)
    for {
      occurredAt <- fields.occurredAt(zone)
      context = Context(row, fields, occurredAt, stateOf(row, canceled))
      operations <- row.section match {
        case IbkrSection.Trade => trade(context)
        case IbkrSection.CashTransaction => cashTransaction(context)
        case IbkrSection.Transfer => transfer(context)
        case IbkrSection.CorporateAction => corporateAction(context)
      }
    } yield operations
  }

  /** `row` paired with its translation. */
  def recordOf(
    row: IbkrStatementRow,
    zone: ZoneId,
    canceled: Set[IbkrTransactionId]
  ): Either[IbkrStatementError, IbkrRowRecord] =
    translate(row, zone, canceled).map(IbkrRowRecord(row, _))

  /** The ids of the trades that the cancelling rows among `rows` name. A trade is cancelled by a
    * row of type `TradeCancel`, or one noted `Ca`, which names it by `origTransactionID`.
    */
  def canceledTransactions(rows: Iterable[IbkrStatementRow]): Set[IbkrTransactionId] =
    rows.iterator
      .filter(isCancellation)
      .flatMap(_.attribute(Name.OrigTransactionId))
      .map(IbkrTransactionId(_))
      .toSet

  private def isCancellation(row: IbkrStatementRow): Boolean =
    row.section == IbkrSection.Trade && (
      row.attribute(Name.TransactionType).exists(CancelTransactionTypes) ||
        row.attribute(Name.Notes).exists(_.split(NoteSeparator).exists(_.trim == CancelNote))
    )

  private def stateOf(row: IbkrStatementRow, canceled: Set[IbkrTransactionId]): OperationState =
    if (isCancellation(row) || (row.section == IbkrSection.Trade && canceled(row.transactionId)))
      OperationState.Canceled
    else OperationState.Executed

  // The kind of an operation that grows what it is about when `amount` is positive and shrinks it
  // when negative; one of no size is `other`.
  private def kindBySign(
    amount: BigDecimal,
    positive: OperationKind,
    negative: OperationKind
  ): OperationKind =
    if (amount.signum > 0) positive
    else if (amount.signum < 0) negative
    else OperationKind.Other

  // What every operation of a row shares.
  private final case class Context(
    row: IbkrStatementRow,
    fields: Fields,
    occurredAt: Instant,
    state: OperationState
  ) {
    def ref: OperationRef = row.ref

    // An operation of the row, which can move a position and cash both.
    def operation(
      ref: OperationRef,
      kind: OperationKind,
      instrument: Option[InstrumentKey],
      quantity: BigDecimal,
      price: Option[BigDecimal],
      amount: BigDecimal,
      currency: Currency,
      brokerType: String
    ): AccountOperation =
      AccountOperation(
        row.accountId,
        ref,
        kind,
        state,
        occurredAt,
        instrument,
        quantity,
        price,
        amount,
        currency,
        BrokerOperationType(brokerType)
      )

    // An operation of the row that moves cash and no position.
    def cash(
      ref: OperationRef,
      kind: OperationKind,
      instrument: Option[InstrumentKey],
      amount: BigDecimal,
      currency: Currency,
      brokerType: String
    ): AccountOperation =
      operation(ref, kind, instrument, Zero, None, amount, currency, brokerType)
  }

  // Reads the attributes of a row, failing with the row's name when one is missing or malformed.
  private final class Fields(row: IbkrStatementRow) {
    private val id = Some(row.transactionId)

    def text(name: String): Option[String] = row.attribute(name)

    def required(name: String): Translation[String] =
      text(name).toRight(IbkrStatementError.MissingAttribute(row.section, id, name))

    def currency(name: String): Translation[Currency] = required(name).map(Currency(_))

    def decimal(name: String): Translation[Option[BigDecimal]] =
      text(name) match {
        case None => Right(None)
        case Some(value) =>
          IbkrAttributes
            .decimal(value)
            .map(Some(_))
            .toRight(IbkrStatementError.InvalidAttribute(row.section, id, name, value))
      }

    def requiredDecimal(name: String): Translation[BigDecimal] =
      decimal(name).flatMap(_.toRight(IbkrStatementError.MissingAttribute(row.section, id, name)))

    def decimalOrZero(name: String): Translation[BigDecimal] = decimal(name).map(_.getOrElse(Zero))

    // The price per unit, where the row has one other than zero.
    def price(name: String): Translation[Option[BigDecimal]] =
      decimal(name).map(_.filter(_.signum != 0))

    def instrument: Option[InstrumentKey] = text(Name.Conid).map(instrumentOf)

    def requiredInstrument: Translation[InstrumentKey] = required(Name.Conid).map(instrumentOf)

    def occurredAt(zone: ZoneId): Translation[Instant] =
      OccurrenceAttributes.flatMap(name => text(name).map(name -> _)).headOption match {
        case Some((name, value)) =>
          IbkrAttributes
            .dateTime(value)
            .toRight(IbkrStatementError.InvalidAttribute(row.section, id, name, value))
            .map(_.atZone(zone).toInstant)
        case None => Right(row.reportDate.atStartOfDay(zone).toInstant)
      }
  }

  private def instrumentOf(conid: String): InstrumentKey =
    InstrumentKey(Broker.Ibkr, BrokerInstrumentId(conid))

  private def trade(context: Context): Translation[List[AccountOperation]] = {
    val fields = context.fields
    for {
      category <- fields.required(Name.AssetCategory)
      currency <- fields.currency(Name.Currency)
      quantity <- fields.requiredDecimal(Name.Quantity)
      proceeds <- fields.requiredDecimal(Name.Proceeds)
      legs <-
        if (category == Category.Stock) stockTrade(context, currency, quantity, proceeds)
        else if (category == Category.Cash) Right(conversion(context, currency, quantity, proceeds))
        else Right(List(otherTrade(context, currency, proceeds)))
      fees <- tradeFees(
        context,
        currency,
        if (category == Category.Cash) None else fields.instrument
      )
    } yield legs ++ fees
  }

  private def stockTrade(
    context: Context,
    currency: Currency,
    quantity: BigDecimal,
    proceeds: BigDecimal
  ): Translation[List[AccountOperation]] = {
    val fields = context.fields
    val kind = kindBySign(quantity, OperationKind.Buy, OperationKind.Sell)
    for {
      instrument <- fields.requiredInstrument
      price <- fields.price(Name.TradePrice)
    } yield List(
      context.operation(
        context.ref,
        kind,
        Some(instrument),
        quantity,
        price,
        proceeds,
        currency,
        fields.text(Name.BuySell).getOrElse(kind.value)
      )
    )
  }

  // `quantity` is in the first currency of the pair and `proceeds` in the second, which is the
  // currency of the trade. Anything that does not read like that is not a conversion.
  private def conversion(
    context: Context,
    currency: Currency,
    quantity: BigDecimal,
    proceeds: BigDecimal
  ): List[AccountOperation] = {
    val firstCurrency = context.fields.text(Name.Symbol).collect {
      case CurrencyPair(first, second) if Currency(second) == currency => Currency(first)
    }
    firstCurrency.fold(List(otherTrade(context, currency, proceeds))) { first =>
      List(
        context.cash(
          context.row.refWith(first.value),
          OperationKind.Exchange,
          None,
          quantity,
          first,
          ExchangeType
        ),
        context.cash(
          context.row.refWith(currency.value),
          OperationKind.Exchange,
          None,
          proceeds,
          currency,
          ExchangeType
        )
      )
    }
  }

  private def otherTrade(
    context: Context,
    currency: Currency,
    proceeds: BigDecimal
  ): AccountOperation =
    context.cash(
      context.ref,
      OperationKind.Other,
      context.fields.instrument,
      proceeds,
      currency,
      context.fields.text(Name.BuySell).getOrElse(OperationKind.Other.value)
    )

  // The commission is in its own currency, which can differ from the trade's; the transaction tax
  // is in the trade's.
  private def tradeFees(
    context: Context,
    currency: Currency,
    instrument: Option[InstrumentKey]
  ): Translation[List[AccountOperation]] = {
    val fields = context.fields
    for {
      commission <- fields.decimalOrZero(Name.IbCommission)
      taxes <- fields.decimalOrZero(Name.Taxes)
    } yield {
      val commissionCurrency =
        fields.text(Name.IbCommissionCurrency).map(Currency(_)).getOrElse(currency)
      List(
        Option.when(commission.signum != 0)(
          context.cash(
            context.row.refWith(CommissionSuffix),
            OperationKind.Fee,
            instrument,
            commission,
            commissionCurrency,
            CommissionType
          )
        ),
        Option.when(taxes.signum != 0)(
          context
            .cash(
              context.row.refWith(TaxesSuffix),
              OperationKind.Fee,
              instrument,
              taxes,
              currency,
              TaxType
            )
        )
      ).flatten
    }
  }

  private def cashTransaction(context: Context): Translation[List[AccountOperation]] = {
    val fields = context.fields
    for {
      cashType <- fields.required(Name.Type)
      amount <- fields.requiredDecimal(Name.Amount)
      currency <- fields.currency(Name.Currency)
    } yield List(
      context.cash(
        context.ref,
        cashKindOf(cashType, amount),
        fields.instrument,
        amount,
        currency,
        cashType
      )
    )
  }

  private def cashKindOf(cashType: String, amount: BigDecimal): OperationKind = {
    val normalized = cashType.toLowerCase(Locale.ROOT)
    if (DepositsAndWithdrawals(normalized))
      kindBySign(amount, OperationKind.Deposit, OperationKind.Withdrawal)
    else CashKinds.getOrElse(normalized, OperationKind.Other)
  }

  // Stock moves in or out with its quantity. Cash that comes with a transfer is an operation of its
  // own, `other` because the statement does not say what the cash is for. A transfer that moves no
  // stock is the cash alone, or nothing.
  private def transfer(context: Context): Translation[List[AccountOperation]] = {
    val fields = context.fields
    val brokerType =
      List(fields.text(Name.Type), fields.text(Name.Direction)).flatten.mkString(" ")
    for {
      currency <- fields.currency(Name.Currency)
      quantity <- fields.decimalOrZero(Name.Quantity)
      cash <- fields.decimalOrZero(Name.CashTransfer)
      stock <-
        if (fields.text(Name.AssetCategory).contains(Category.Stock) && quantity.signum != 0)
          stockTransfer(context, currency, quantity, brokerType).map(Some(_))
        else Right(None)
    } yield {
      def cashOperation(ref: OperationRef) =
        context.cash(ref, OperationKind.Other, fields.instrument, cash, currency, brokerType)
      stock match {
        case Some(moved) =>
          moved :: Option
            .when(cash.signum != 0)(cashOperation(context.row.refWith(CashSuffix)))
            .toList
        case None => List(cashOperation(context.ref))
      }
    }
  }

  // The direction of the transfer decides whether the stock comes in or goes out; the sign of the
  // quantity does when the row names no direction.
  private def stockTransfer(
    context: Context,
    currency: Currency,
    quantity: BigDecimal,
    brokerType: String
  ): Translation[AccountOperation] = {
    val fields = context.fields
    val signed = fields.text(Name.Direction).map(_.toUpperCase(Locale.ROOT)) match {
      case Some(InDirection) => quantity.abs
      case Some(OutDirection) => -quantity.abs
      case Some(_) | None => quantity
    }
    for {
      instrument <- fields.requiredInstrument
      price <- fields.price(Name.TransferPrice)
    } yield context.operation(
      context.ref,
      kindBySign(signed, OperationKind.SecurityIn, OperationKind.SecurityOut),
      Some(instrument),
      signed,
      price,
      Zero,
      currency,
      brokerType
    )
  }

  // Each leg moves its instrument by its signed quantity and carries the cash of the action in its
  // proceeds.
  private def corporateAction(context: Context): Translation[List[AccountOperation]] = {
    val fields = context.fields
    for {
      currency <- fields.currency(Name.Currency)
      quantity <- fields.decimalOrZero(Name.Quantity)
      proceeds <- fields.decimalOrZero(Name.Proceeds)
      instrument <-
        if (quantity.signum != 0) fields.requiredInstrument.map(Some(_))
        else Right(fields.instrument)
    } yield {
      val kind = kindBySign(quantity, OperationKind.SecurityIn, OperationKind.SecurityOut)
      List(
        context.operation(
          context.ref,
          kind,
          instrument,
          quantity,
          None,
          proceeds,
          currency,
          fields.text(Name.Type).getOrElse(kind.value)
        )
      )
    }
  }
}
