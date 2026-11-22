package st.btu.trading.pillar.testkit

import cats.Applicative
import cats.effect.{Clock, IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.{Json, JsonObject, parser}
import st.btu.trading.pillar.gateways.binance.*
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.{AccountId, AccountOperation}
import st.btu.trading.pillar.storages.{
  AccountOperationStorage,
  BinanceMinutePriceStorage,
  BinancePairStorage,
  BinanceRecordStorage
}

import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import scala.concurrent.duration.*

object BinanceFakes {

  /** The permissions of a key that reads and does nothing else, restricted to an IP address. */
  val readOnlyKey: BinanceKeyPermissions = BinanceKeyPermissions(
    ipRestricted = true,
    enableReading = true,
    otherFlags = Map(
      "enableWithdrawals" -> false,
      "enableInternalTransfer" -> false,
      "permitsUniversalTransfer" -> false,
      "enableSpotAndMarginTrading" -> false,
      "enableMargin" -> false,
      "enableFutures" -> false
    )
  )

  def at(text: String): Instant = Instant.parse(text)

  def asset(code: String): BinanceAsset = BinanceAsset(code)

  def symbol(code: String): BinanceSymbol = BinanceSymbol(code)

  def pairOf(base: String, quote: String): BinancePair =
    BinancePair(BinanceSymbol.of(asset(base), asset(quote)), asset(base), asset(quote))

  /** The bar of `pair` that starts at `minute`. */
  def minute(pair: String, start: String): BinanceMinute =
    BinanceMinute(symbol(pair), at(start))

  /** A JSON object parsed from text. */
  def obj(text: String): JsonObject =
    parser
      .parse(text)
      .toOption
      .flatMap(_.asObject)
      .getOrElse(throw new AssertionError(s"not a JSON object: $text"))

  /** A fill of an order, as `myTrades` lists it. */
  def trade(
    symbol: String,
    id: Long,
    time: String,
    price: String,
    qty: String,
    quoteQty: String,
    isBuyer: Boolean,
    commission: String = "0",
    commissionAsset: String = "BNB"
  ): JsonObject =
    obj(s"""{"symbol":"$symbol","id":$id,"orderId":100234,"orderListId":-1,"price":"$price",
         |"qty":"$qty","quoteQty":"$quoteQty","commission":"$commission",
         |"commissionAsset":"$commissionAsset","time":${at(time).toEpochMilli},
         |"isBuyer":$isBuyer,"isMaker":false,"isBestMatch":true}""".stripMargin)

  /** A crypto deposit, as `capital/deposit/hisrec` lists it. */
  def deposit(id: String, coin: String, amount: String, time: String, status: Int = 1): JsonObject =
    obj(s"""{"id":"$id","amount":"$amount","coin":"$coin","network":"ETH","status":$status,
         |"address":"0xabc","txId":"0xdef","insertTime":${at(time).toEpochMilli},
         |"transferType":0,"confirmTimes":"12/12"}""".stripMargin)

  private val ApplyTimeFormat =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

  /** A crypto withdrawal, as `capital/withdraw/history` lists it. */
  def withdrawal(
    id: String,
    coin: String,
    amount: String,
    fee: String,
    time: String,
    status: Int = 6
  ): JsonObject =
    obj(s"""{"id":"$id","amount":"$amount","transactionFee":"$fee","coin":"$coin","status":$status,
         |"address":"0xabc","txId":"0xdef","applyTime":"${ApplyTimeFormat.format(at(time))}",
         |"network":"ETH","transferType":0}""".stripMargin)

  /** A fiat deposit or withdrawal, as `fiat/orders` lists it. */
  def fiatOrder(
    orderNo: String,
    currency: String,
    indicated: String,
    amount: String,
    fee: String,
    time: String,
    status: String = "Successful"
  ): JsonObject =
    obj(s"""{"orderNo":"$orderNo","fiatCurrency":"$currency","indicatedAmount":"$indicated",
         |"amount":"$amount","totalFee":"$fee","method":"BankTransfer","status":"$status",
         |"createTime":${at(time).toEpochMilli},"updateTime":${at(
            time
          ).toEpochMilli}}""".stripMargin)

  /** A purchase or sale of a coin with fiat money, as `fiat/payments` lists it. */
  def fiatPayment(
    orderNo: String,
    fiat: String,
    coin: String,
    sourceAmount: String,
    obtainAmount: String,
    fee: String,
    price: String,
    time: String,
    method: String = "Credit Card",
    status: String = "Completed"
  ): JsonObject =
    obj(s"""{"orderNo":"$orderNo","sourceAmount":"$sourceAmount","fiatCurrency":"$fiat",
         |"obtainAmount":"$obtainAmount","cryptoCurrency":"$coin","totalFee":"$fee","price":"$price",
         |"status":"$status","paymentMethod":"$method","createTime":${at(time).toEpochMilli},
         |"updateTime":${at(time).toEpochMilli}}""".stripMargin)

  /** A Convert order, as `convert/tradeFlow` lists it. */
  def convert(
    orderId: String,
    from: String,
    fromAmount: String,
    to: String,
    toAmount: String,
    time: String,
    status: String = "SUCCESS"
  ): JsonObject =
    obj(
      s"""{"quoteId":"f3b91c525b2644c7bc1e1cd31b6e1aa6","orderId":$orderId,"orderStatus":"$status",
         |"fromAsset":"$from","fromAmount":"$fromAmount","toAsset":"$to","toAmount":"$toAmount",
         |"ratio":"0.1","inverseRatio":"10","createTime":${at(time).toEpochMilli}}""".stripMargin
    )

  /** A distribution or an airdrop, or the debit of one, as `asset/assetDividend` lists it:
    * `direction` is 1 for a credit and -1 for a debit.
    */
  def distribution(
    id: Long,
    asset: String,
    amount: String,
    time: String,
    direction: Int = 1
  ): JsonObject =
    obj(s"""{"id":$id,"amount":"$amount","asset":"$asset","divTime":${at(time).toEpochMilli},
         |"enInfo":"$asset distribution","tranId":2968885920,"direction":$direction}""".stripMargin)

  /** One detail of a dust conversion: `amount` of `from` converted into `received` of `target`, net
    * of `charge`, as `asset/dribblet` lists it. A detail with no target is one from before the
    * target could be chosen.
    */
  def dustDetail(
    transId: Long,
    from: String,
    amount: String,
    received: String,
    charge: String,
    time: String,
    target: Option[String] = Some("BNB")
  ): JsonObject = {
    val targetField = target.fold("")(coin => s""","targetAsset":"$coin"""")
    obj(s"""{"transId":$transId,"serviceChargeAmount":"$charge","amount":"$amount",
         |"operateTime":${at(time).toEpochMilli},"transferedAmount":"$received",
         |"fromAsset":"$from"$targetField}""".stripMargin)
  }

  /** A conversion of dust with the details given, as `asset/dribblet` lists it. */
  def dustConversion(transId: Long, time: String, details: JsonObject*): JsonObject = {
    val detailList = Json.arr(details.map(Json.fromJsonObject)*).noSpaces
    obj(s"""{"operateTime":${at(time).toEpochMilli},"totalTransferedAmount":"0.001",
         |"totalServiceChargeAmount":"0.00002","transId":$transId,
         |"userAssetDribbletDetails":$detailList}""".stripMargin)
  }

  /** An operation as a spec compares it: its ref, kind, instrument id, quantity, price, amount,
    * currency and broker type.
    */
  type Shown =
    (String, String, Option[String], BigDecimal, Option[BigDecimal], BigDecimal, String, String)

  def shown(operation: AccountOperation): Shown =
    (
      operation.ref.value,
      operation.kind.value,
      operation.instrument.map(_.id.value),
      operation.quantity,
      operation.price,
      operation.amount,
      operation.currency.value,
      operation.brokerType.value
    )

  /** A record built from fixture JSON, which must be of a shape `BinanceRecord.of` accepts. */
  def record(accountId: AccountId, source: BinanceSource, attributes: JsonObject): BinanceRecord =
    BinanceRecord
      .of(accountId, source, attributes)
      .fold(error => throw new AssertionError(error.getMessage), identity)

  // The names of the storages written, in the order they were written.
  type EventLog = Ref[IO, List[String]]

  def eventLog: IO[EventLog] = Ref.of[IO, List[String]](List.empty)

  trait FakeBinanceRecordStorage extends BinanceRecordStorage[IO] with AccountOperationStorage[IO] {

    /** How many records each `record` call carried, oldest first. */
    def recordedBatchSizes: IO[List[Int]]

    /** Every stored record of every account, oldest stored first. */
    def storedRecords: IO[List[BinanceRecord]]

    /** Every stored translation. */
    def storedTranslations: IO[List[AccountOperation]]
  }

  // `record` upserts as the contract says: a record by (account, source, record id), and the
  // translations of the record as a whole.
  def recordStorage(
    records: List[BinanceRecord] = List.empty,
    translations: List[AccountOperation] = List.empty,
    log: Option[EventLog] = None
  ): IO[FakeBinanceRecordStorage] =
    for {
      recordsRef <- Ref.of[IO, List[BinanceRecord]](records)
      translationsRef <- Ref.of[IO, List[AccountOperation]](translations)
      batchSizesRef <- Ref.of[IO, List[Int]](List.empty)
    } yield new FakeBinanceRecordStorage {
      def record(translated: List[BinanceTranslatedRecord]): IO[Unit] =
        log.fold(IO.unit)(_.update(_ :+ "records")) >>
          batchSizesRef.update(_ :+ translated.size) >>
          recordsRef.update(translated.map(_.record).foldLeft(_)(upsertRecord)) >>
          translationsRef.update(translated.foldLeft(_)(replaceTranslations))

      def latestOccurredAt(accountId: AccountId, source: BinanceSource): IO[Option[Instant]] =
        recordsRef.get.map(
          _.filter(r => r.accountId == accountId && r.source == source)
            .map(_.occurredAt)
            .maxByOption(_.toEpochMilli)
        )

      def records(accountId: AccountId): IO[Vector[BinanceRecord]] =
        recordsRef.get.map(_.filter(_.accountId == accountId).toVector)

      def lastTradeIds(accountId: AccountId): IO[Map[BinanceSymbol, BinanceTradeId]] =
        recordsRef.get.map { all =>
          all
            .filter(r => r.accountId == accountId && r.source == BinanceSource.Trade)
            .flatMap(r => r.tradeSymbol.zip(BinanceRecord.tradeIdOf(r.attributes)))
            .groupMapReduce { case (symbol, _) => symbol } { case (_, id) => id }((a, b) =>
              if (a.value >= b.value) a else b
            )
        }

      def operations(
        accountId: AccountId,
        since: Instant,
        until: Instant
      ): IO[Vector[AccountOperation]] =
        translationsRef.get.map(_.filter { op =>
          op.accountId == accountId && !op.occurredAt.isBefore(since) &&
          op.occurredAt.isBefore(until)
        }.sortBy(op => (op.occurredAt, op.ref.value)).toVector)

      def recordedBatchSizes: IO[List[Int]] = batchSizesRef.get

      def storedRecords: IO[List[BinanceRecord]] = recordsRef.get

      def storedTranslations: IO[List[AccountOperation]] = translationsRef.get
    }

  private def upsertRecord(
    stored: List[BinanceRecord],
    record: BinanceRecord
  ): List[BinanceRecord] = {
    val at = stored.indexWhere(s =>
      s.accountId == record.accountId && s.source == record.source &&
        s.recordId == record.recordId
    )
    if (at < 0) stored :+ record else stored.updated(at, record)
  }

  // The translations of the record that the record does not carry are removed, and those it
  // carries are stored by (account, ref).
  private def replaceTranslations(
    stored: List[AccountOperation],
    translated: BinanceTranslatedRecord
  ): List[AccountOperation] = {
    val record = translated.record
    val kept = stored.filterNot(op =>
      op.accountId == record.accountId && record.owns(op.ref) &&
        !translated.translations.exists(_.ref == op.ref)
    )
    translated.translations.foldLeft(kept) { (current, translation) =>
      val at =
        current.indexWhere(s => s.accountId == translation.accountId && s.ref == translation.ref)
      if (at < 0) current :+ translation else current.updated(at, translation)
    }
  }

  trait FakeBinancePairStorage extends BinancePairStorage[IO] {
    def stored: IO[List[BinancePair]]
  }

  def pairStorage(
    initial: List[BinancePair] = List.empty,
    log: Option[EventLog] = None
  ): IO[FakeBinancePairStorage] =
    Ref.of[IO, Map[BinanceSymbol, BinancePair]](initial.map(p => p.symbol -> p).toMap).map { ref =>
      new FakeBinancePairStorage {
        def upsert(pairs: List[BinancePair]): IO[Unit] =
          log.fold(IO.unit)(_.update(_ :+ "pairs")) >>
            ref.update(pairs.foldLeft(_)((stored, p) => stored.updated(p.symbol, p)))

        def all: IO[List[BinancePair]] = ref.get.map(_.values.toList)

        def stored: IO[List[BinancePair]] = all
      }
    }

  trait FakeBinanceMinutePriceStorage extends BinanceMinutePriceStorage[IO] {

    /** The stored closes. */
    def stored: IO[Map[BinanceMinute, BigDecimal]]

    /** The prices of each `save` call, oldest first. */
    def saves: IO[List[List[BinanceMinutePrice]]]
  }

  def minutePriceStorage(
    initial: Map[BinanceMinute, BigDecimal] = Map.empty
  ): IO[FakeBinanceMinutePriceStorage] =
    for {
      ref <- Ref.of[IO, Map[BinanceMinute, BigDecimal]](initial)
      savesRef <- Ref.of[IO, List[List[BinanceMinutePrice]]](List.empty)
    } yield new FakeBinanceMinutePriceStorage {
      def closes(minutes: Set[BinanceMinute]): IO[Map[BinanceMinute, BigDecimal]] =
        ref.get.map(_.filter { case (minute, _) => minutes(minute) })

      def save(prices: List[BinanceMinutePrice]): IO[Unit] =
        savesRef.update(_ :+ prices) >>
          ref.update(prices.foldLeft(_)((stored, p) => stored.updated(p.minute, p.close)))

      def stored: IO[Map[BinanceMinute, BigDecimal]] = ref.get

      def saves: IO[List[List[BinanceMinutePrice]]] = savesRef.get
    }

  /** What a fake `BinanceApi` was asked, in the order it was asked. */
  enum Call {
    case Restrictions
    case ExchangeInfo
    case DelistSchedule
    case Coins
    case SpotBalances
    case FundingBalances
    case Trades(symbol: BinanceSymbol, fromId: BinanceTradeId)

    /** A read of the history of a source other than trades, from `from` to `to`. */
    case Window(source: BinanceSource, from: Instant, to: Instant)
    case Minute(minute: BinanceMinute)
  }

  /** What a fake `BinanceApi` answers.
    *
    * @param trades
    *   the trades of every pair, which `myTrades` answers from the id asked for, at most a page of
    *   them, oldest first
    * @param history
    *   the records of each source other than trades, of which a read answers those whose time is
    *   within its window
    * @param spot
    *   the balances Binance reports in the spot wallet
    * @param funding
    *   the balances Binance reports in the funding wallet
    * @param closes
    *   the bars that exist
    * @param failures
    *   the calls that fail, with what they fail with
    */
  final case class ApiScript(
    permissions: BinanceKeyPermissions = readOnlyKey,
    pairs: List[BinancePair] = List.empty,
    delisting: List[BinanceSymbol] = List.empty,
    coinNames: Map[BinanceAsset, String] = Map.empty,
    trades: List[JsonObject] = List.empty,
    history: Map[BinanceSource, List[JsonObject]] = Map.empty,
    spot: Map[BinanceAsset, BigDecimal] = Map.empty,
    funding: Map[BinanceAsset, BigDecimal] = Map.empty,
    closes: Map[BinanceMinute, BigDecimal] = Map.empty,
    failures: PartialFunction[Call, Throwable] = PartialFunction.empty
  )

  trait FakeBinanceApi extends BinanceApi[IO] {

    /** The calls made, oldest first. */
    def calls: IO[List[Call]]

    /** From now on, the records of `source` that a read answers from are `records`. */
    def setHistory(source: BinanceSource, records: List[JsonObject]): IO[Unit]

    /** From now on, the balances that Binance reports are `spot` and `funding`. */
    def setBalances(
      spot: Map[BinanceAsset, BigDecimal],
      funding: Map[BinanceAsset, BigDecimal] = Map.empty
    ): IO[Unit]
  }

  def binanceApi(script: ApiScript = ApiScript()): IO[FakeBinanceApi] =
    for {
      callsRef <- Ref.of[IO, List[Call]](List.empty)
      historyRef <- Ref.of[IO, Map[BinanceSource, List[JsonObject]]](script.history)
      balancesRef <- Ref.of[IO, (Map[BinanceAsset, BigDecimal], Map[BinanceAsset, BigDecimal])](
        (script.spot, script.funding)
      )
    } yield {
      def called(call: Call): IO[Unit] =
        callsRef.update(_ :+ call) >> script.failures.lift(call).fold(IO.unit)(IO.raiseError)

      // The records of `source` whose time is within the window.
      def within(source: BinanceSource, from: Instant, to: Instant): IO[List[JsonObject]] =
        called(Call.Window(source, from, to)) >> historyRef.get.map(
          _.getOrElse(source, Nil).filter { attributes =>
            val at = record(AccountId(0), source, attributes).occurredAt
            !at.isBefore(from) && !at.isAfter(to)
          }
        )

      new FakeBinanceApi {
        def apiRestrictions: IO[BinanceKeyPermissions] =
          called(Call.Restrictions).as(script.permissions)

        def exchangeInfo: IO[List[BinancePair]] = called(Call.ExchangeInfo).as(script.pairs)

        def delistSchedule: IO[List[BinanceSymbol]] =
          called(Call.DelistSchedule).as(script.delisting)

        def coins: IO[Map[BinanceAsset, String]] =
          called(Call.Coins).as(script.coinNames)

        def spotBalances: IO[Map[BinanceAsset, BigDecimal]] =
          called(Call.SpotBalances) >> balancesRef.get.map { case (spot, _) => spot }

        def fundingBalances: IO[Map[BinanceAsset, BigDecimal]] =
          called(Call.FundingBalances) >> balancesRef.get.map { case (_, funding) => funding }

        def myTrades(symbol: BinanceSymbol, fromId: BinanceTradeId): IO[List[JsonObject]] =
          called(Call.Trades(symbol, fromId)).as(
            script.trades
              .filter(t => t("symbol").flatMap(_.asString).contains(symbol.value))
              .filter(t => BinanceRecord.tradeIdOf(t).exists(_.value >= fromId.value))
              .sortBy(t => BinanceRecord.tradeIdOf(t).map(_.value))
              .take(BinanceApi.TradesPageSize)
          )

        def deposits(from: Instant, to: Instant): IO[List[JsonObject]] =
          within(BinanceSource.Deposit, from, to)

        def withdrawals(from: Instant, to: Instant): IO[List[JsonObject]] =
          within(BinanceSource.Withdrawal, from, to)

        def fiatOrders(
          direction: FiatOrderDirection,
          from: Instant,
          to: Instant
        ): IO[List[JsonObject]] =
          within(
            direction match {
              case FiatOrderDirection.Deposit => BinanceSource.FiatDeposit
              case FiatOrderDirection.Withdrawal => BinanceSource.FiatWithdrawal
            },
            from,
            to
          )

        def fiatPayments(
          direction: FiatPaymentDirection,
          from: Instant,
          to: Instant
        ): IO[List[JsonObject]] =
          within(
            direction match {
              case FiatPaymentDirection.Buy => BinanceSource.FiatBuy
              case FiatPaymentDirection.Sell => BinanceSource.FiatSell
            },
            from,
            to
          )

        def convertTradeFlow(from: Instant, to: Instant): IO[List[JsonObject]] =
          within(BinanceSource.Convert, from, to)

        def assetDividends(from: Instant, to: Instant): IO[List[JsonObject]] =
          within(BinanceSource.Dividend, from, to)

        def dustLog(from: Instant, to: Instant): IO[List[JsonObject]] =
          within(BinanceSource.Dust, from, to)

        def minuteClose(symbol: BinanceSymbol, minute: Instant): IO[Option[BigDecimal]] = {
          val bar = BinanceMinute(symbol, minute)
          called(Call.Minute(bar)).as(script.closes.get(bar))
        }

        def dailyBars(
          symbol: BinanceSymbol,
          from: LocalDate,
          to: LocalDate
        ): IO[List[BinanceDailyBar]] =
          IO.raiseError(new UnsupportedOperationException("the sync reads no daily bars"))

        def lastPrice(symbol: BinanceSymbol): IO[Option[BigDecimal]] =
          IO.raiseError(new UnsupportedOperationException("the sync reads no last price"))

        def calls: IO[List[Call]] = callsRef.get

        def setHistory(source: BinanceSource, records: List[JsonObject]): IO[Unit] =
          historyRef.update(_.updated(source, records))

        def setBalances(
          spot: Map[BinanceAsset, BigDecimal],
          funding: Map[BinanceAsset, BigDecimal]
        ): IO[Unit] =
          balancesRef.set((spot, funding))
      }
    }

  /** What a fake `BinanceMarket` was asked, in the order it was asked. */
  enum MarketCall {
    case ExchangeInfo
    case DailyBars(symbol: BinanceSymbol, from: LocalDate, to: LocalDate)
    case LastPrice(symbol: BinanceSymbol)
  }

  /** What a fake `BinanceMarket` answers.
    *
    * @param pairs
    *   the pairs `exchangeInfo` lists
    * @param bars
    *   the daily bars of each pair, oldest first; a pair that is not here is unknown to Binance
    * @param prices
    *   the last price of each pair; a pair that is not here has none
    * @param failures
    *   the calls that fail, with what they fail with
    */
  final case class MarketScript(
    pairs: List[BinancePair] = List.empty,
    bars: Map[BinanceSymbol, List[BinanceDailyBar]] = Map.empty,
    prices: Map[BinanceSymbol, BigDecimal] = Map.empty,
    failures: PartialFunction[MarketCall, Throwable] = PartialFunction.empty
  )

  trait FakeBinanceMarket extends BinanceMarket[IO] {

    /** The calls made, oldest first. */
    def calls: IO[List[MarketCall]]
  }

  def binanceMarket(script: MarketScript = MarketScript()): IO[FakeBinanceMarket] =
    Ref.of[IO, List[MarketCall]](List.empty).map { callsRef =>
      def called(call: MarketCall): IO[Unit] =
        callsRef.update(_ :+ call) >> script.failures.lift(call).fold(IO.unit)(IO.raiseError)

      new FakeBinanceMarket {
        def exchangeInfo: IO[List[BinancePair]] = called(MarketCall.ExchangeInfo).as(script.pairs)

        def minuteClose(symbol: BinanceSymbol, minute: Instant): IO[Option[BigDecimal]] =
          IO.raiseError(new UnsupportedOperationException("the market data source reads no minute"))

        def dailyBars(symbol: BinanceSymbol, from: LocalDate, to: LocalDate)
          : IO[List[BinanceDailyBar]] =
          called(MarketCall.DailyBars(symbol, from, to)) >> script.bars
            .get(symbol)
            .fold(IO.raiseError[List[BinanceDailyBar]](unknownSymbol))(bars =>
              IO.pure(bars.filter(bar => !bar.date.isBefore(from) && !bar.date.isAfter(to)))
            )

        def lastPrice(symbol: BinanceSymbol): IO[Option[BigDecimal]] =
          called(MarketCall.LastPrice(symbol)).as(script.prices.get(symbol))

        def calls: IO[List[MarketCall]] = callsRef.get
      }
    }

  // How Binance refuses a pair it does not know.
  private def unknownSymbol: BinanceError =
    BinanceError.Refused(BinanceError.InvalidSymbol, "Invalid symbol.")

  /** A daily bar that opens on `date` and closes at the end of that day, with the prices given. */
  def dailyBar(
    date: String,
    open: String,
    high: String,
    low: String,
    close: String,
    volume: String
  ): BinanceDailyBar = {
    val day = LocalDate.parse(date)
    BinanceDailyBar(
      day,
      BigDecimal(open),
      BigDecimal(high),
      BigDecimal(low),
      BigDecimal(close),
      BigDecimal(volume),
      day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.minusMillis(1)
    )
  }

  /** The pair and the first id of each read of trades. */
  def tradeReads(calls: List[Call]): List[(String, Long)] =
    calls.collect { case Call.Trades(pair, fromId) => (pair.value, fromId.value) }

  /** The window of each read of the source. */
  def windowsRead(calls: List[Call], source: BinanceSource): List[(Instant, Instant)] =
    calls.collect { case Call.Window(read, from, to) if read == source => (from, to) }

  /** Reads of the trades of the pair `pairName` that fail. */
  def failingTrades(pairName: String): PartialFunction[Call, Throwable] = {
    case Call.Trades(pair, _) if pair.value == pairName => new RuntimeException("boom")
  }

  /** The `ref` of each operation, as text. */
  def operationRefs(operations: Iterable[AccountOperation]): List[String] =
    operations.map(_.ref.value).toList

  /** The `ref` of each record, as text. */
  def recordRefs(records: Iterable[BinanceRecord]): List[String] =
    records.map(_.ref.value).toList

  /** The symbols of pairs, as text. */
  def symbolNames(pairs: List[BinancePair]): List[String] = pairs.map(_.symbol.value)

  /** The pair of each bar asked for. */
  def barsRead(calls: List[Call]): List[String] =
    calls.collect { case Call.Minute(bar) => bar.symbol.value }

  /** A clock that reads the instant it was moved to, and a way to move it forward. */
  final case class SteppingClock(clock: Clock[IO], advance: Duration => IO[Unit])

  def steppingClock(start: Instant): IO[SteppingClock] =
    Ref.of[IO, Instant](start).map { ref =>
      SteppingClock(
        new Clock[IO] {
          def applicative: Applicative[IO] = Applicative[IO]

          def monotonic: IO[FiniteDuration] = IO.pure(Duration.ZERO.toNanos.nanos)

          def realTime: IO[FiniteDuration] =
            ref.get.map(now => FiniteDuration(now.getEpochSecond, SECONDS) + now.getNano.nanos)
        },
        step => ref.update(_.plus(step))
      )
    }
}
