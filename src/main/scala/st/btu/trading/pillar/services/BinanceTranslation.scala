package st.btu.trading.pillar.services

import cats.data.NonEmptyList
import cats.syntax.traverse.*
import io.circe.JsonObject
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.services.BinancePrices.*
import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.temporal.ChronoUnit

/** Translates the records of a Binance account into `AccountOperation`s, purely: one record gives
  * one or more operations whose refs all start with the record's own, so that the storage replaces
  * them as a whole. `docs/binance.md` tables the rules under "Translation".
  */
private[services] object BinanceTranslation {

  /** What translating a record needs to know besides the record.
    *
    * @param cashAssets
    *   the assets that are currencies and not instruments
    * @param priceQuotes
    *   the cash assets a coin is priced in, in order of preference
    * @param pairs
    *   the pairs trades were made on, by symbol
    */
  final case class Context(
    cashAssets: Set[BinanceAsset],
    priceQuotes: NonEmptyList[BinanceAsset],
    pairs: Map[BinanceSymbol, BinancePair]
  ) {
    def isCash(asset: BinanceAsset): Boolean = cashAssets(asset)

    def firstQuote: BinanceAsset = priceQuotes.head
  }

  /** The translation of a record.
    *
    * @param examined
    *   every bar whose close the translation looked at, in order, which is every bar it might use
    *   when no close is known: the bars to load the stored closes of
    * @param pending
    *   the first bar, before the one whose close the translation used, that no close is known for
    *   and that is not known to be absent, which the sync asks Binance for before it translates the
    *   record again
    */
  final case class Result(
    operations: List[AccountOperation],
    notes: List[BinanceNote],
    examined: List[BinanceMinute],
    pending: Option[BinanceMinute]
  )

  /** The operations of `record` with the closes in `book`, or the reason it cannot be translated: a
    * field it needs is missing or malformed, or a trade is on a pair that is not known.
    */
  def translate(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] =
    record.source match {
      case BinanceSource.Trade => trade(record, context, book)
      case BinanceSource.Convert => convert(record, context, book)
      case BinanceSource.Deposit => deposit(record, context, book)
      case BinanceSource.Withdrawal => withdrawal(record, context, book)
      case BinanceSource.FiatDeposit => fiatOrder(record, context, book, FiatOrderKind.Deposit)
      case BinanceSource.FiatWithdrawal =>
        fiatOrder(record, context, book, FiatOrderKind.Withdrawal)
      case BinanceSource.FiatBuy => fiatBuy(record, context, book)
      case BinanceSource.FiatSell => fiatSell(record, context, book)
      case BinanceSource.Dividend => distribution(record, context, book)
      case BinanceSource.Dust => dust(record, context, book)
    }

  private val Zero = BigDecimal(0)

  private val ReceivedSuffix = "received"
  private val FeeSuffix = "fee"
  private val FeeSaleSuffix = "fee-sale"
  private val DepositSuffix = "deposit"

  private val CashBalance = "cash balance"

  // The coin dust is converted into when a detail of the log names none: before the owner could
  // choose another coin, the log held conversions into BNB alone.
  private val DustTarget = BinanceAsset("BNB")

  private val DistributionType = "Distribution"
  private val DustType = "Dust"

  // The fields of the records that the translation reads, as Binance names them.
  private object Field {
    val Amount = "amount"
    val Asset = "asset"
    val Coin = "coin"
    val Commission = "commission"
    val CommissionAsset = "commissionAsset"
    val CryptoCurrency = "cryptoCurrency"
    val Direction = "direction"
    val FiatCurrency = "fiatCurrency"
    val FromAmount = "fromAmount"
    val FromAsset = "fromAsset"
    val IndicatedAmount = "indicatedAmount"
    val IsBuyer = "isBuyer"
    val ObtainAmount = "obtainAmount"
    val OrderStatus = "orderStatus"
    val PaymentMethod = "paymentMethod"
    val Price = "price"
    val Qty = "qty"
    val QuoteQty = "quoteQty"
    val SourceAmount = "sourceAmount"
    val Status = "status"
    val Symbol = "symbol"
    val ToAmount = "toAmount"
    val ToAsset = "toAsset"
    val TargetAsset = "targetAsset"
    val TotalFee = "totalFee"
    val TransactionFee = "transactionFee"
    val TransferedAmount = "transferedAmount"
    val TransId = "transId"
    val UserAssetDribbletDetails = "userAssetDribbletDetails"
  }

  // Which way a distribution moved a coin, as Binance's `direction` says: 1 credits the account and
  // -1 debits it.
  private enum Direction(val code: String) {
    case Credit extends Direction("1")
    case Debit extends Direction("-1")
  }

  // How a record's status field maps to the state of its operations; a status that is not listed is
  // still in progress, and so pending. Keys are lower-cased.
  private val DepositStates: Map[String, OperationState] = Map(
    "1" -> OperationState.Executed,
    "6" -> OperationState.Executed,
    "2" -> OperationState.Canceled,
    "7" -> OperationState.Canceled
  )

  private val WithdrawalStates: Map[String, OperationState] = Map(
    "6" -> OperationState.Executed,
    "1" -> OperationState.Canceled,
    "3" -> OperationState.Canceled,
    "5" -> OperationState.Canceled
  )

  private val FiatOrderStates: Map[String, OperationState] = Map(
    "successful" -> OperationState.Executed,
    "finished" -> OperationState.Executed,
    "failed" -> OperationState.Canceled,
    "refunded" -> OperationState.Canceled,
    "expired" -> OperationState.Canceled
  )

  private val FiatPaymentStates: Map[String, OperationState] = Map(
    "completed" -> OperationState.Executed,
    "failed" -> OperationState.Canceled,
    "refunded" -> OperationState.Canceled
  )

  private val ConvertStates: Map[String, OperationState] =
    Map("success" -> OperationState.Executed, "fail" -> OperationState.Canceled)

  private def stateOf(states: Map[String, OperationState], status: String): OperationState =
    states.getOrElse(status.toLowerCase, OperationState.Pending)

  private enum FiatOrderKind(val brokerType: String) {
    case Deposit extends FiatOrderKind("Fiat deposit")
    case Withdrawal extends FiatOrderKind("Fiat withdrawal")
  }

  // What a fee is paid for, which names the operations that carry it.
  private enum FeeKind(val brokerType: String) {
    case Commission extends FeeKind("Commission")
    case NetworkFee extends FeeKind("Network fee")
  }

  private val FiatFee = "Fiat fee"

  private final case class Amount(asset: BinanceAsset, quantity: BigDecimal)

  // The operations of a record, with the notes about how they were made.
  private final case class Built(operations: List[AccountOperation], notes: List[BinanceNote]) {
    def ++(other: Built): Built = Built(operations ++ other.operations, notes ++ other.notes)
  }

  private object Built {
    val Empty: Built = Built(Nil, Nil)

    def of(operations: AccountOperation*): Built = Built(operations.toList, Nil)
  }

  // The operations that stand for what an exchange gives and receives, and the currency of what they
  // are valued in: the one a commission in a coin is valued in first.
  private final case class Legs(
    operations: List[AccountOperation],
    notes: List[BinanceNote],
    currency: BinanceAsset
  )

  private def result(looked: Looked[Built]): Result =
    Result(looked.value.operations, looked.value.notes, looked.examined, looked.pending)

  private def instrumentOf(asset: BinanceAsset): InstrumentKey =
    InstrumentKey(Broker.Binance, BrokerInstrumentId(asset.value))

  private def currencyOf(asset: BinanceAsset): Currency = Currency(asset.value)

  // The price of a unit that `amount` paid for `quantity` of, where `quantity` is not zero.
  private def unitPrice(amount: BigDecimal, quantity: BigDecimal): Option[BigDecimal] =
    Option.when(quantity.signum != 0)(divide(amount.abs, quantity.abs))

  // What every operation of a record shares, and the means to build them.
  private final case class Frame(
    record: BinanceRecord,
    context: Context,
    book: PriceBook,
    state: OperationState,
    brokerType: String,
    detail: Option[String] = None
  ) {

    def withBrokerType(named: String): Frame = copy(brokerType = named)

    // The frame of one detail of a record that holds several exchanges, whose operations are told
    // apart by the detail's key.
    def ofDetail(key: String): Frame = copy(detail = Some(key))

    // The bar of `asset` against `quote` that holds the record's time.
    def candidate(asset: BinanceAsset, quote: BinanceAsset): Candidate =
      Candidate(
        BinanceMinute(
          BinanceSymbol.of(asset, quote),
          record.occurredAt.truncatedTo(ChronoUnit.MINUTES)
        ),
        quote
      )

    def candidates(asset: BinanceAsset, quotes: List[BinanceAsset]): List[Candidate] =
      quotes.map(candidate(asset, _))

    // An operation of the record, which can move a position and cash both.
    def operation(
      ref: OperationRef,
      kind: OperationKind,
      instrument: Option[BinanceAsset],
      quantity: BigDecimal,
      price: Option[BigDecimal],
      amount: BigDecimal,
      currency: BinanceAsset,
      group: Option[OperationGroup] = None
    ): AccountOperation =
      AccountOperation(
        record.accountId,
        ref,
        kind,
        state,
        record.occurredAt,
        instrument.map(instrumentOf),
        quantity,
        price,
        amount,
        currencyOf(currency),
        BrokerOperationType(brokerType),
        group
      )

    // An operation of the record that moves cash and no position.
    def cash(
      ref: OperationRef,
      kind: OperationKind,
      instrument: Option[BinanceAsset],
      amount: BigDecimal,
      currency: BinanceAsset
    ): AccountOperation =
      operation(ref, kind, instrument, Zero, None, amount, currency)

    // Units of a coin that move with no cash: the currency is the first price quote.
    def units(
      ref: OperationRef,
      kind: OperationKind,
      coin: BinanceAsset,
      quantity: BigDecimal,
      group: Option[OperationGroup] = None
    ): AccountOperation =
      operation(ref, kind, Some(coin), quantity, None, Zero, context.firstQuote, group)

    def own: OperationRef = detail.fold(record.ref)(record.refWith)

    def received: OperationRef =
      detail.fold(record.refWith(ReceivedSuffix))(key =>
        record.refWith(s"$key${BinanceRecord.RefSeparator}$ReceivedSuffix")
      )
  }

  // What an exchange of one asset for another gives up and receives.
  //   - `tradePrice` is the price per unit of the coin that Binance reports, where the coin is the
  //     base of a pair quoted in cash; any other price is derived from the amounts.
  //   - `commission` is what Binance charged, if anything.
  private final case class Swap(
    gave: Amount,
    got: Amount,
    tradePrice: Option[BigDecimal],
    commission: Option[Amount]
  )

  private def trade(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      symbol <- fields.required(Field.Symbol).map(BinanceSymbol(_))
      pair <- context.pairs
        .get(symbol)
        .toRight(BinanceRecordError.UnknownPair(record.recordId, symbol))
      price <- fields.requiredDecimal(Field.Price)
      quantity <- fields.requiredDecimal(Field.Qty)
      quoteQuantity <- fields.requiredDecimal(Field.QuoteQty)
      buyer <- fields.requiredBoolean(Field.IsBuyer)
      commission <- commissionOf(fields)
    } yield {
      val base = Amount(pair.baseAsset, quantity)
      val quote = Amount(pair.quoteAsset, quoteQuantity)
      val (gave, got) = if (buyer) (quote, base) else (base, quote)
      val reported = Option.when(context.isCash(quote.asset) && !context.isCash(base.asset))(price)
      val frame =
        Frame(record, context, book, OperationState.Executed, if (buyer) "Buy" else "Sell")
      result(swap(frame, Swap(gave, got, reported, commission)))
    }
  }

  private def commissionOf(fields: BinanceFields): Either[BinanceRecordError, Option[Amount]] =
    fields.decimalOrZero(Field.Commission).flatMap { quantity =>
      if (quantity.signum == 0) Right(None)
      else fields.asset(Field.CommissionAsset).map(asset => Some(Amount(asset, quantity)))
    }

  private def convert(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      fromAsset <- fields.asset(Field.FromAsset)
      fromAmount <- fields.requiredDecimal(Field.FromAmount)
      toAsset <- fields.asset(Field.ToAsset)
      toAmount <- fields.requiredDecimal(Field.ToAmount)
      status <- fields.required(Field.OrderStatus)
    } yield {
      val frame = Frame(record, context, book, stateOf(ConvertStates, status), "Convert")
      result(
        swap(frame, Swap(Amount(fromAsset, fromAmount), Amount(toAsset, toAmount), None, None))
      )
    }
  }

  // The legs of the exchange, by what each side is, and then its commission.
  private def swap(frame: Frame, exchange: Swap): Looked[Built] = {
    val Swap(gave, got, tradePrice, commission) = exchange
    val context = frame.context
    val gaveCash = context.isCash(gave.asset)
    val gotCash = context.isCash(got.asset)
    // A commission paid in the coin received comes off the quantity received.
    val inGot = commission.filter(c => !context.isCash(c.asset) && c.asset == got.asset)
    val gotNet = got.quantity - inGot.fold(Zero)(_.quantity)
    val charged = commission.filter(c => !inGot.contains(c))
    // The coin a fee is for: the one coin of the exchange, or the coin received when both are coins.
    val coinTraded =
      if (gaveCash && gotCash) None
      else if (gaveCash) Some(got.asset)
      else if (gotCash) Some(gave.asset)
      else Some(got.asset)

    val legs: Looked[Legs] =
      (gaveCash, gotCash) match {
        case (true, true) => Looked.pure(exchangeLegs(frame, gave, got))
        case (true, false) => Looked.pure(buyLegs(frame, gave, got, gotNet, tradePrice))
        case (false, true) => Looked.pure(sellLegs(frame, gave, got, tradePrice))
        case (false, false) => coinLegs(frame, gave, got, gotNet)
      }
    for {
      legs <- legs
      fee <- feeOperations(frame, charged, Some(legs.currency), coinTraded, FeeKind.Commission)
    } yield Built(legs.operations, legs.notes) ++ fee
  }

  // Two cash assets: the cash given up leaves and the cash received comes in.
  private def exchangeLegs(frame: Frame, gave: Amount, got: Amount): Legs =
    Legs(
      List(
        frame.cash(frame.own, OperationKind.Exchange, None, -gave.quantity, gave.asset),
        frame.cash(frame.received, OperationKind.Exchange, None, got.quantity, got.asset)
      ),
      Nil,
      gave.asset
    )

  // Cash given up for a coin.
  private def buyLegs(
    frame: Frame,
    gave: Amount,
    got: Amount,
    gotNet: BigDecimal,
    reported: Option[BigDecimal]
  ): Legs =
    Legs(
      List(
        frame.operation(
          frame.own,
          OperationKind.Buy,
          Some(got.asset),
          gotNet,
          reported.orElse(unitPrice(gave.quantity, got.quantity)),
          -gave.quantity,
          gave.asset
        )
      ),
      Nil,
      gave.asset
    )

  // A coin given up for cash.
  private def sellLegs(
    frame: Frame,
    gave: Amount,
    got: Amount,
    reported: Option[BigDecimal]
  ): Legs =
    Legs(
      List(
        frame.operation(
          frame.own,
          OperationKind.Sell,
          Some(gave.asset),
          -gave.quantity,
          reported.orElse(unitPrice(got.quantity, gave.quantity)),
          got.quantity,
          got.asset
        )
      ),
      Nil,
      got.asset
    )

  // A coin given up for another coin. The coin given up is priced first and the coin received
  // after it, each by the price quotes in order. The price fixes one amount, in one currency, that
  // the coin given up is sold for and the coin received is bought with.
  private def coinLegs(
    frame: Frame,
    gave: Amount,
    got: Amount,
    gotNet: BigDecimal
  ): Looked[Legs] = {
    val quotes = frame.context.priceQuotes.toList
    val byGave = frame.candidates(gave.asset, quotes)
    val byGot = frame.candidates(got.asset, quotes)
    frame.book.first(byGave ++ byGot).map {
      case Some(Found(candidate, close)) =>
        val value = (if (byGave.contains(candidate)) gave.quantity else got.quantity) * close
        val currency = candidate.quote
        Legs(
          List(
            frame.operation(
              frame.own,
              OperationKind.Sell,
              Some(gave.asset),
              -gave.quantity,
              unitPrice(value, gave.quantity),
              value,
              currency
            ),
            frame.operation(
              frame.received,
              OperationKind.Buy,
              Some(got.asset),
              gotNet,
              unitPrice(value, gotNet),
              -value,
              currency
            )
          ),
          Nil,
          currency
        )
      case None =>
        val group = Some(OperationGroup(frame.own.value))
        Legs(
          List(
            frame.units(frame.own, OperationKind.Conversion, gave.asset, -gave.quantity, group),
            frame.units(frame.received, OperationKind.Conversion, got.asset, gotNet, group)
          ),
          List(BinanceNote(frame.own, BinanceNote.Reason.LegsUnvalued)),
          frame.context.firstQuote
        )
    }
  }

  // What a fee costs, for the coin it was charged on, if one. `ownCurrency` is the currency of what
  // the fee was charged on, which a fee paid in a coin is valued in first, so that it joins the
  // fees of its own trade.
  private def feeOperations(
    frame: Frame,
    fee: Option[Amount],
    ownCurrency: Option[BinanceAsset],
    chargedOn: Option[BinanceAsset],
    kind: FeeKind
  ): Looked[Built] =
    fee match {
      case None => Looked.pure(Built.Empty)
      case Some(Amount(asset, quantity)) if frame.context.isCash(asset) =>
        Looked.pure(
          Built.of(
            frame
              .withBrokerType(kind.brokerType)
              .cash(frame.record.refWith(FeeSuffix), OperationKind.Fee, chargedOn, -quantity, asset)
          )
        )
      case Some(Amount(asset, quantity)) =>
        val quotes = (ownCurrency.toList ++ frame.context.priceQuotes.toList).distinct
        frame.book.first(frame.candidates(asset, quotes)).map {
          case Some(Found(candidate, close)) =>
            val value = quantity * close
            val currency = candidate.quote
            val typed = frame.withBrokerType(kind.brokerType)
            Built.of(
              typed.operation(
                frame.record.refWith(FeeSaleSuffix),
                OperationKind.Sell,
                Some(asset),
                -quantity,
                Some(close),
                value,
                currency
              ),
              typed.cash(
                frame.record.refWith(FeeSuffix),
                OperationKind.Fee,
                chargedOn,
                -value,
                currency
              )
            )
          case None =>
            Built(
              List(
                frame
                  .withBrokerType(kind.brokerType)
                  .units(
                    frame.record.refWith(FeeSuffix),
                    OperationKind.SecurityOut,
                    asset,
                    -quantity
                  )
              ),
              List(BinanceNote(frame.record.ref, unvalued(kind, asset, quantity)))
            )
        }
    }

  private def unvalued(
    kind: FeeKind,
    asset: BinanceAsset,
    quantity: BigDecimal
  ): BinanceNote.Reason =
    kind match {
      case FeeKind.Commission => BinanceNote.Reason.CommissionUnvalued(asset, quantity)
      case FeeKind.NetworkFee => BinanceNote.Reason.NetworkFeeUnvalued(asset, quantity)
    }

  private def deposit(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      coin <- fields.asset(Field.Coin)
      amount <- fields.requiredDecimal(Field.Amount)
      status <- fields.required(Field.Status)
    } yield {
      val frame = Frame(record, context, book, stateOf(DepositStates, status), "Deposit")
      val operation =
        if (context.isCash(coin))
          frame.cash(frame.own, OperationKind.Deposit, None, amount, coin)
        else frame.units(frame.own, OperationKind.SecurityIn, coin, amount)
      result(Looked.pure(Built.of(operation)))
    }
  }

  // The coin leaves with its network fee, which is paid like a commission in a coin.
  private def withdrawal(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      coin <- fields.asset(Field.Coin)
      amount <- fields.requiredDecimal(Field.Amount)
      networkFee <- fields.decimalOrZero(Field.TransactionFee)
      status <- fields.required(Field.Status)
    } yield {
      val frame = Frame(record, context, book, stateOf(WithdrawalStates, status), "Withdrawal")
      val operation =
        if (context.isCash(coin))
          frame.cash(frame.own, OperationKind.Withdrawal, None, -amount, coin)
        else frame.units(frame.own, OperationKind.SecurityOut, coin, -amount)
      val fee = Option.when(networkFee.signum != 0)(Amount(coin, networkFee))
      result(
        feeOperations(frame, fee, None, None, FeeKind.NetworkFee).map(Built.of(operation) ++ _)
      )
    }
  }

  private def fiatOrder(
    record: BinanceRecord,
    context: Context,
    book: PriceBook,
    kind: FiatOrderKind
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      currency <- fields.asset(Field.FiatCurrency)
      amount <- fields.requiredDecimal(kind match {
        case FiatOrderKind.Deposit => Field.IndicatedAmount
        case FiatOrderKind.Withdrawal => Field.Amount
      })
      fee <- fields.decimalOrZero(Field.TotalFee)
      status <- fields.required(Field.Status)
    } yield {
      val frame = Frame(record, context, book, stateOf(FiatOrderStates, status), kind.brokerType)
      val order = kind match {
        case FiatOrderKind.Deposit =>
          frame.cash(frame.own, OperationKind.Deposit, None, amount, currency)
        case FiatOrderKind.Withdrawal =>
          frame.cash(frame.own, OperationKind.Withdrawal, None, -amount, currency)
      }
      result(Looked.pure(Built(order :: fiatFee(frame, fee, None, currency).toList, Nil)))
    }
  }

  private def fiatFee(
    frame: Frame,
    fee: BigDecimal,
    chargedOn: Option[BinanceAsset],
    currency: BinanceAsset
  ): Option[AccountOperation] =
    Option.when(fee.signum != 0)(
      frame
        .withBrokerType(FiatFee)
        .cash(frame.record.refWith(FeeSuffix), OperationKind.Fee, chargedOn, -fee, currency)
    )

  // The coin bought with fiat money. The fiat paid, less the fee, is what the coin costs; the fee
  // is the coin's cost too, as a fee naming it. The fiat arrived in the account in a deposit of its
  // own unless it was paid from the account's balance. A coin that counts as cash is exchanged for
  // the fiat and is no instrument, so its fee names none.
  private def fiatBuy(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      fiat <- fields.asset(Field.FiatCurrency)
      coin <- fields.asset(Field.CryptoCurrency)
      paid <- fields.requiredDecimal(Field.SourceAmount)
      obtained <- fields.requiredDecimal(Field.ObtainAmount)
      fee <- fields.decimalOrZero(Field.TotalFee)
      price <- fields.decimal(Field.Price)
      status <- fields.required(Field.Status)
    } yield {
      val frame = Frame(record, context, book, stateOf(FiatPaymentStates, status), "Buy crypto")
      val cost = paid - fee
      val fromBalance = fields.text(Field.PaymentMethod).exists(_.equalsIgnoreCase(CashBalance))
      val deposit = Option.when(!fromBalance)(
        frame.cash(record.refWith(DepositSuffix), OperationKind.Deposit, None, paid, fiat)
      )
      val purchase =
        if (context.isCash(coin))
          List(
            frame.cash(frame.own, OperationKind.Exchange, None, -cost, fiat),
            frame.cash(frame.received, OperationKind.Exchange, None, obtained, coin)
          )
        else
          List(
            frame.operation(
              frame.own,
              OperationKind.Buy,
              Some(coin),
              obtained,
              price.orElse(unitPrice(cost, obtained)),
              -cost,
              fiat
            )
          )
      val charged = Option.unless(context.isCash(coin))(coin)
      result(
        Looked.pure(Built(deposit.toList ++ purchase ++ fiatFee(frame, fee, charged, fiat), Nil))
      )
    }
  }

  // The coin sold for fiat money, which stays in the account. The fiat obtained is net of the fee,
  // so the sale is for the two together.
  private def fiatSell(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      fiat <- fields.asset(Field.FiatCurrency)
      coin <- fields.asset(Field.CryptoCurrency)
      sold <- fields.requiredDecimal(Field.SourceAmount)
      obtained <- fields.requiredDecimal(Field.ObtainAmount)
      fee <- fields.decimalOrZero(Field.TotalFee)
      price <- fields.decimal(Field.Price)
      status <- fields.required(Field.Status)
    } yield {
      val frame = Frame(record, context, book, stateOf(FiatPaymentStates, status), "Sell crypto")
      val proceeds = obtained + fee
      val sale =
        if (context.isCash(coin))
          List(
            frame.cash(frame.own, OperationKind.Exchange, None, -sold, coin),
            frame.cash(frame.received, OperationKind.Exchange, None, proceeds, fiat)
          )
        else
          List(
            frame.operation(
              frame.own,
              OperationKind.Sell,
              Some(coin),
              -sold,
              price.orElse(unitPrice(proceeds, sold)),
              proceeds,
              fiat
            )
          )
      val charged = Option.unless(context.isCash(coin))(coin)
      result(Looked.pure(Built(sale ++ fiatFee(frame, fee, charged, fiat), Nil)))
    }
  }

  // A distribution or an airdrop. A coin credited is a reward of its units, valued at the close of
  // the bar that holds the time; cash credited is a reward of the cash itself. A debit takes the
  // units of a coin out at their cost, and charges cash as a fee that names no instrument.
  private def distribution(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] = {
    val fields = record.fields
    for {
      coin <- fields.asset(Field.Asset)
      amount <- fields.requiredDecimal(Field.Amount)
      direction <- directionOf(record, fields)
    } yield {
      val frame = Frame(record, context, book, OperationState.Executed, DistributionType)
      result(distributed(frame, coin, amount.abs, direction))
    }
  }

  private def directionOf(
    record: BinanceRecord,
    fields: BinanceFields
  ): Either[BinanceRecordError, Direction] =
    fields.required(Field.Direction).flatMap { code =>
      Direction.values
        .find(_.code == code)
        .toRight(
          BinanceRecordError
            .InvalidField(record.source, Some(record.recordId), Field.Direction, code)
        )
    }

  private def distributed(
    frame: Frame,
    coin: BinanceAsset,
    amount: BigDecimal,
    direction: Direction
  ): Looked[Built] =
    (direction, frame.context.isCash(coin)) match {
      case (Direction.Credit, true) =>
        Looked.pure(Built.of(frame.cash(frame.own, OperationKind.Reward, None, amount, coin)))
      case (Direction.Credit, false) =>
        frame.book.first(frame.candidates(coin, frame.context.priceQuotes.toList)).map {
          case Some(Found(candidate, close)) =>
            Built.of(
              frame.operation(
                frame.own,
                OperationKind.Reward,
                Some(coin),
                amount,
                Some(close),
                Zero,
                candidate.quote
              )
            )
          case None =>
            Built(
              List(frame.units(frame.own, OperationKind.Reward, coin, amount)),
              List(BinanceNote(frame.own, BinanceNote.Reason.DistributionUnvalued(coin, amount)))
            )
        }
      case (Direction.Debit, true) =>
        Looked.pure(Built.of(frame.cash(frame.own, OperationKind.Fee, None, -amount, coin)))
      case (Direction.Debit, false) =>
        Looked.pure(Built.of(frame.units(frame.own, OperationKind.SecurityOut, coin, -amount)))
    }

  // One conversion of dust: `from` is what a detail gave up and `target` what it received, net of
  // the service charge.
  private final case class DustDetail(id: String, from: Amount, target: Amount)

  // The details of a conversion each make an exchange of their own, so that what one gives up is
  // never shared with what another receives.
  private def dust(
    record: BinanceRecord,
    context: Context,
    book: PriceBook
  ): Either[BinanceRecordError, Result] =
    for {
      objects <- record.fields.objects(Field.UserAssetDribbletDetails)
      details <- objects.traverse(dustDetail(record, _))
    } yield {
      val frame = Frame(record, context, book, OperationState.Executed, DustType)
      val operations = keyed(details).flatMap { case (key, detail) =>
        dustOperations(frame.ofDetail(key), detail.from, detail.target)
      }
      result(Looked.pure(Built(operations, Nil)))
    }

  private def dustDetail(
    record: BinanceRecord,
    detail: JsonObject
  ): Either[BinanceRecordError, DustDetail] = {
    val fields = new BinanceFields(record.source, Some(record.recordId), detail)
    for {
      id <- fields.required(Field.TransId)
      from <- fields.asset(Field.FromAsset)
      amount <- fields.requiredDecimal(Field.Amount)
      received <- fields.requiredDecimal(Field.TransferedAmount)
    } yield {
      val target = fields.text(Field.TargetAsset).fold(DustTarget)(BinanceAsset(_))
      DustDetail(id, Amount(from, amount), Amount(target, received))
    }
  }

  // What tells a detail's operations from those of the others: its own `transId`, with the asset it
  // gave up added where several details of the conversion carry the same one.
  private def keyed(details: List[DustDetail]): List[(String, DustDetail)] = {
    val shared = details.groupBy(_.id).collect { case (id, same) if same.size > 1 => id }.toSet
    details.map { detail =>
      val key = if (shared(detail.id)) s"${detail.id}-${detail.from.asset.value}" else detail.id
      key -> detail
    }
  }

  private def dustOperations(frame: Frame, gave: Amount, got: Amount): List[AccountOperation] =
    (frame.context.isCash(gave.asset), frame.context.isCash(got.asset)) match {
      case (true, true) => exchangeLegs(frame, gave, got).operations
      case (true, false) => buyLegs(frame, gave, got, got.quantity, None).operations
      case (false, true) => sellLegs(frame, gave, got, None).operations
      case (false, false) =>
        val group = Some(OperationGroup(frame.own.value))
        List(
          frame.units(frame.own, OperationKind.Conversion, gave.asset, -gave.quantity, group),
          frame.units(frame.received, OperationKind.Conversion, got.asset, got.quantity, group)
        )
    }
}
