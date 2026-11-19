package st.btu.trading.pillar.services

import cats.effect.{Clock, Ref, Sync}
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import io.circe.JsonObject
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.gateways.binance.{BinanceApi, FiatOrderDirection, FiatPaymentDirection}
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.{Broker, OperationRef, OperationState}
import st.btu.trading.pillar.services.BinancePrices.PriceBook
import st.btu.trading.pillar.services.BinanceTranslation.Context
import st.btu.trading.pillar.storages.{
  BinanceMinutePriceStorage,
  BinancePairStorage,
  BinanceRecordStorage,
  BrokerInstrumentStorage
}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{Duration, Instant, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

final class BinanceSyncServiceImpl[F[_]: Logging] private (
  account: BinanceAccount,
  config: BinanceSyncConfig,
  api: BinanceApi[F],
  records: BinanceRecordStorage[F],
  pairStorage: BinancePairStorage[F],
  priceStorage: BinanceMinutePriceStorage[F],
  instruments: BrokerInstrumentStorage[F],
  systemAlerts: SystemAlerts[F],
  memory: BinanceSyncServiceImpl.Memory[F]
)(implicit F: Sync[F], clock: Clock[F])
    extends BinanceSyncService[F] {
  import BinanceSyncServiceImpl.*

  private val accountId = account.accountId

  // The coins an account received from a record in this run, with the record's operation.
  private final case class Received(ref: OperationRef, asset: BinanceAsset)

  // One run: when it started, the pairs Binance lists, how translations see them, and the names of
  // the coins, which are read when first needed.
  private final case class Run(
    now: Instant,
    listed: Map[BinanceSymbol, BinancePair],
    names: Ref[F, Option[Map[BinanceAsset, String]]]
  ) {
    def context: Context = Context(config.cashAssets, config.priceQuotes, listed)
  }

  // A source other than trades: how long a window it can be read in, and the call that reads one.
  private final case class Feed(
    source: BinanceSource,
    length: FiniteDuration,
    read: (Instant, Instant) => F[List[JsonObject]]
  )

  private val feeds: List[Feed] = List(
    Feed(BinanceSource.Deposit, HistoryWindow, api.deposits),
    Feed(BinanceSource.Withdrawal, HistoryWindow, api.withdrawals),
    Feed(
      BinanceSource.FiatDeposit,
      HistoryWindow,
      api.fiatOrders(FiatOrderDirection.Deposit, _, _)
    ),
    Feed(
      BinanceSource.FiatWithdrawal,
      HistoryWindow,
      api.fiatOrders(FiatOrderDirection.Withdrawal, _, _)
    ),
    Feed(BinanceSource.FiatBuy, HistoryWindow, api.fiatPayments(FiatPaymentDirection.Buy, _, _)),
    Feed(BinanceSource.FiatSell, HistoryWindow, api.fiatPayments(FiatPaymentDirection.Sell, _, _)),
    Feed(BinanceSource.Convert, ConvertWindow, api.convertTradeFlow)
  )

  def sync: F[Unit] =
    for {
      now <- clock.realTimeInstant
      _ <- info"Syncing the Binance account ${accountId.value}"
      _ <- checkKey
      pairs <- api.exchangeInfo
      names <- Ref.of[F, Option[Map[BinanceAsset, String]]](None)
      run = Run(now, pairs.map(pair => pair.symbol -> pair).toMap, names)
      _ <- describeRebuilt(run)
      received <- feeds.flatTraverse(readFeed(run, _))
      _ <- readTrades(run, received)
    } yield ()

  def rebuildTranslations: F[Unit] =
    for {
      stored <- records.records(accountId)
      pairs <- pairStorage.all
      context = Context(config.cashAssets, config.priceQuotes, pairs.map(p => p.symbol -> p).toMap)
      ordered = stored.toList.sortBy(record => (record.occurredAt.toEpochMilli, record.ref.value))
      _ <- info"Recording again the ${ordered.size} stored Binance records with their translations"
      _ <- ordered.grouped(RecordBatchSize).toList.traverse_(rebuildBatch(context, _))
    } yield ()

  // The translations come from the stored prices alone: a close that is not stored is not looked up.
  private def rebuildBatch(context: Context, batch: List[BinanceRecord]): F[Unit] =
    for {
      blind <- batch.traverse(BinanceTranslation.translate(_, context, PriceBook()).liftTo[F])
      stored <- priceStorage.closes(blind.flatMap(_.examined).toSet)
      results <- batch.traverse(
        BinanceTranslation.translate(_, context, PriceBook(stored)).liftTo[F]
      )
      _ <- announce(results.flatMap(_.notes))
      _ <- records.record(translated(batch, results))
      _ <- memory.rebuilt.update(_ ++ BinanceInstruments.coinsOf(results.flatMap(_.operations)))
    } yield ()

  // The rebuild cannot describe the coins its translations name, since a ticker needs the pairs
  // Binance lists, so the next run describes them, once, and so also a coin that only a change to
  // the translation brought in.
  private def describeRebuilt(run: Run): F[Unit] =
    for {
      coins <- memory.rebuilt.get
      _ <- recordInstruments(run, coins)
      _ <- memory.rebuilt.set(Set.empty)
    } yield ()

  private def translated(
    batch: List[BinanceRecord],
    results: List[BinanceTranslation.Result]
  ): List[BinanceTranslatedRecord] =
    batch.zip(results).map { case (record, result) =>
      BinanceTranslatedRecord(record, result.operations)
    }

  // The bot never trades or moves funds, so a key that could is refused before anything is read.
  private def checkKey: F[Unit] =
    for {
      permissions <- api.apiRestrictions
      _ <- permissions.readOnly.liftTo[F]
      _ <- F.whenA(!permissions.ipRestricted)(
        warn"The Binance API key of account ${accountId.value} is not restricted to an IP address"
      )
    } yield ()

  // Reads the source window by window, up to the start of the run, recording each window before the
  // next is read. Gives the coins the records brought into the account.
  private def readFeed(run: Run, feed: Feed): F[List[Received]] =
    for {
      from <- startOf(feed.source)
      windows = windowsBetween(from, run.now, feed.length)
      _ <-
        info"Reading the ${feed.source.value} records of account ${accountId.value} in ${windows.size} windows from $from"
      received <- windows.flatTraverse(readWindow(run, feed, _))
    } yield received

  private def readWindow(run: Run, feed: Feed, window: Window): F[List[Received]] =
    for {
      objects <- feed.read(window.from, window.to)
      read <- objects.traverse(BinanceRecord.of(accountId, feed.source, _)).liftTo[F]
      stored <- store(run, read)
      _ <- memory.cursors.update(_.updated(feed.source, window.to))
    } yield stored.flatMap(received)

  // Where the next read of a source starts: a little before where this process last read it, else a
  // little before the latest record stored for it, else the start of the history.
  private def startOf(source: BinanceSource): F[Instant] =
    memory.cursors.get.flatMap(_.get(source) match {
      case Some(readUntil) => F.pure(readUntil.minus(reread))
      case None =>
        records
          .latestOccurredAt(accountId, source)
          .map(_.fold(historyStart)(_.minus(reread)))
    })

  private def reread: Duration = Duration.ofDays(config.rereadDays.toLong)

  private def historyStart: Instant = config.historyStart.atStartOfDay(ZoneOffset.UTC).toInstant

  // The coins the executed operations of a record bring in.
  private def received(translated: BinanceTranslatedRecord): List[Received] =
    translated.translations.collect {
      case operation
          if operation.state == OperationState.Executed && operation.quantity.signum > 0 =>
        operation.instrument
          .filter(_.broker == Broker.Binance)
          .map(key => Received(operation.ref, BinanceAsset(key.id.value)))
    }.flatten

  private def readTrades(run: Run, brought: List[Received]): F[Unit] =
    for {
      known <- records.lastTradeIds(accountId)
      scanDue <- fullScanDue(run.now)
      counted <- memory.counted.get
      arrived = brought.filterNot(b => counted(b.ref)).map(_.asset).toSet
      symbols <-
        if (scanDue) F.pure(run.listed.keySet)
        else announced.map(known.keySet ++ pairsOf(run, arrived) ++ _)
      wanted = symbols.filter(run.listed.contains).toList.sorted
      _ <- info"Reading the trades of account ${accountId.value} on ${wanted.size} pairs"
      _ <- wanted.traverse_(symbol => readPair(run, symbol, known.get(symbol)))
      _ <- memory.counted.update(_ ++ brought.map(_.ref))
      _ <- F.whenA(scanDue)(memory.lastScan.set(Some(run.now)))
    } yield ()

  private def fullScanDue(now: Instant): F[Boolean] =
    memory.lastScan.get.map(
      _.forall(last => Duration.between(last, now).compareTo(rescanInterval) >= 0)
    )

  private def rescanInterval: Duration = Duration.ofNanos(config.pairsRescanInterval.toNanos)

  // The pairs Binance has announced it will delist, which are read while it still lists them.
  private def announced: F[Set[BinanceSymbol]] = api.delistSchedule.map(_.toSet)

  private def pairsOf(run: Run, assets: Set[BinanceAsset]): Set[BinanceSymbol] =
    run.listed.values.collect {
      case pair if assets(pair.baseAsset) || assets(pair.quoteAsset) => pair.symbol
    }.toSet

  // Reads the trades of a pair from the id after the last stored, a page at a time up to a short
  // page. A page that brings no id past where it started ends the reading, so that an answer that
  // ignores the id it was asked from cannot keep it going.
  private def readPair(run: Run, symbol: BinanceSymbol, last: Option[BinanceTradeId]): F[Unit] = {
    def page(from: BinanceTradeId): F[Unit] =
      api.myTrades(symbol, from).flatMap { trades =>
        if (trades.isEmpty) F.unit
        else
          for {
            read <- trades.traverse(BinanceRecord.of(accountId, BinanceSource.Trade, _)).liftTo[F]
            _ <- store(run, read)
            next = trades.flatMap(trade => BinanceRecord.tradeIdOf(trade)).maxOption.map(_.next)
            more = next
              .filter(id => trades.size >= BinanceApi.TradesPageSize && id.value > from.value)
            _ <- more.traverse_(page)
          } yield ()
      }
    page(last.fold(BinanceTradeId.Start)(_.next))
  }

  private def store(run: Run, read: List[BinanceRecord]): F[List[BinanceTranslatedRecord]] =
    distinctRecords(read).grouped(RecordBatchSize).toList.flatTraverse(storeBatch(run, _))

  // The prices come first, then the pairs of the trades and the coins' instruments, then the notes
  // about what no price could value, and the batch last: a failure before the batch is stored
  // leaves the records to be read again, which a failure after it would not.
  private def storeBatch(run: Run, batch: List[BinanceRecord]): F[List[BinanceTranslatedRecord]] =
    for {
      results <- priced(run, batch)
      all = translated(batch, results)
      tradedPairs = batch.flatMap(_.tradeSymbol).distinct.flatMap(run.listed.get)
      _ <- F.whenA(tradedPairs.nonEmpty)(pairStorage.upsert(tradedPairs))
      _ <- recordInstruments(run, BinanceInstruments.coinsOf(all.flatMap(_.translations)))
      _ <- announce(results.flatMap(_.notes))
      _ <- records.record(all)
    } yield all

  // Translates each record of the batch with the closes it needs: the stored ones, and for a bar
  // that is not stored the ones read from Binance, which are stored before the batch is.
  private def priced(run: Run, batch: List[BinanceRecord]): F[List[BinanceTranslation.Result]] =
    for {
      blind <- batch.traverse(BinanceTranslation.translate(_, run.context, PriceBook()).liftTo[F])
      stored <- priceStorage.closes(blind.flatMap(_.examined).toSet)
      done <- batch.foldLeftM(
        (PriceBook(stored), List.empty[BinanceMinutePrice], List.empty[BinanceTranslation.Result])
      ) { case ((book, fetched, results), record) =>
        resolve(run, record, book, fetched).map { case (result, resolved, nowFetched) =>
          (resolved, nowFetched, result :: results)
        }
      }
      (_, fetched, results) = done
      _ <- F.whenA(fetched.nonEmpty)(priceStorage.save(fetched.reverse))
    } yield results.reverse

  // Translates the record again for each bar it needs that is not known yet, after reading that bar
  // from Binance, until it needs none. A pair Binance does not list has no bar to read.
  private def resolve(
    run: Run,
    record: BinanceRecord,
    book: PriceBook,
    fetched: List[BinanceMinutePrice]
  ): F[(BinanceTranslation.Result, PriceBook, List[BinanceMinutePrice])] =
    BinanceTranslation.translate(record, run.context, book).liftTo[F].flatMap { result =>
      result.pending match {
        case None => F.pure((result, book, fetched))
        case Some(minute) =>
          lookUp(run, minute).flatMap {
            case Some(close) =>
              clock.realTimeInstant.flatMap { now =>
                resolve(
                  run,
                  record,
                  book.withClose(minute, close),
                  BinanceMinutePrice(minute, close, now) :: fetched
                )
              }
            case None => resolve(run, record, book.withoutBar(minute), fetched)
          }
      }
    }

  private def lookUp(run: Run, minute: BinanceMinute): F[Option[BigDecimal]] =
    if (run.listed.contains(minute.symbol)) api.minuteClose(minute.symbol, minute.start)
    else F.pure(None)

  private def recordInstruments(run: Run, coins: Set[BinanceAsset]): F[Unit] =
    F.whenA(coins.nonEmpty) {
      for {
        names <- namesOf(run)
        now <- clock.realTimeInstant.map(ZonedDateTime.ofInstant(_, ZoneOffset.UTC))
        _ <- BinanceInstruments
          .of(coins, names, run.listed.keySet, config.priceQuotes, now)
          .traverse_(instruments.upsert)
      } yield ()
    }

  // The names of the coins, read once in a run. A coin without one is named by its asset code, so
  // a failure to read them is no reason to leave the records unrecorded.
  private def namesOf(run: Run): F[Map[BinanceAsset, String]] =
    run.names.get.flatMap {
      case Some(names) => F.pure(names)
      case None =>
        api.coins
          .handleErrorWith { error =>
            warnCause"The names of the Binance coins could not be read; the asset codes stand in for them" (
              error
            ).as(Map.empty[BinanceAsset, String])
          }
          .flatTap(names => run.names.set(Some(names)))
    }

  // Raising comes before remembering what was raised, so that an alert that fails to go out is
  // tried again at the next sync.
  private def announce(notes: List[BinanceNote]): F[Unit] =
    for {
      known <- memory.raised.get
      fresh = notes.filterNot(known).distinct
      _ <- F.whenA(fresh.nonEmpty)(
        systemAlerts.raise(
          s"$UnvaluedAlert:${accountId.value}",
          s"Binance records without a price need a look: account ${accountId.value}",
          fresh
            .map(BinanceNote.describe)
            .mkString(
              s"Records of account ${accountId.value} that no price could value:\n",
              "\n",
              ""
            )
        ) >> memory.raised.update(_ ++ fresh)
      )
    } yield ()
}

object BinanceSyncServiceImpl {

  // How many records a storage call records.
  private val RecordBatchSize = 500

  // How much history one call of each endpoint can be asked for: Binance takes windows of under 90
  // days for deposits, withdrawals and fiat money, and of at most 30 days for Convert.
  private val HistoryWindow = 89.days
  private val ConvertWindow = 30.days

  private val UnvaluedAlert = "binance-unvalued"

  // What the sync keeps between runs of one process, so that a loop that crashes and starts again
  // carries on where it was: the end of each source's last read, when every pair was last read,
  // the notes already raised, the operations whose coins already brought their pairs into a run,
  // and the coins the rebuild named, which the next run describes.
  private final case class Memory[F[_]](
    cursors: Ref[F, Map[BinanceSource, Instant]],
    lastScan: Ref[F, Option[Instant]],
    raised: Ref[F, Set[BinanceNote]],
    counted: Ref[F, Set[OperationRef]],
    rebuilt: Ref[F, Set[BinanceAsset]]
  )

  // The times of one read of a source, both ends included.
  private final case class Window(from: Instant, to: Instant)

  // The time from `from` to `to` in consecutive windows of `length`, the last one cut short at `to`,
  // each ending a millisecond before the next starts.
  private def windowsBetween(from: Instant, to: Instant, length: FiniteDuration): List[Window] =
    Iterator
      .iterate(from)(_.plusMillis(length.toMillis))
      .takeWhile(!_.isAfter(to))
      .map { start =>
        val end = start.plusMillis(length.toMillis - 1)
        Window(start, if (end.isAfter(to)) to else end)
      }
      .toList

  // Of records that share a source and an id, the last one stands: a storage call must not record
  // one record twice, and a history paged while it grows can give the same record on two pages.
  private def distinctRecords(read: List[BinanceRecord]): List[BinanceRecord] =
    read.reverse.distinctBy(record => (record.source, record.recordId)).reverse

  /** Wires a [[BinanceSyncService]] that syncs `account` from `api` into the storages, and raises a
    * system alert through `systemAlerts` for the records no price could value.
    *
    * The alert is `binance-unvalued:<account id>`. It names each record by its `ref` and says what
    * the sync did instead, and never the key. What the sync keeps from one run to the next, the end
    * of each source's last read, when every pair was last read, the alerts raised and the coins the
    * rebuild named, is in memory of the service, so a loop that starts again with the same service
    * carries on, and a new process starts from the storage again.
    */
  def create[I[_]: Sync, F[_]: Sync](
    account: BinanceAccount,
    config: BinanceSyncConfig,
    api: BinanceApi[F],
    records: BinanceRecordStorage[F],
    pairs: BinancePairStorage[F],
    prices: BinanceMinutePriceStorage[F],
    instruments: BrokerInstrumentStorage[F],
    systemAlerts: SystemAlerts[F]
  )(implicit logs: Logs[I, F], clock: Clock[F]): I[BinanceSyncService[F]] =
    for {
      cursors <- Ref.in[I, F, Map[BinanceSource, Instant]](Map.empty)
      lastScan <- Ref.in[I, F, Option[Instant]](None)
      raised <- Ref.in[I, F, Set[BinanceNote]](Set.empty)
      counted <- Ref.in[I, F, Set[OperationRef]](Set.empty)
      rebuilt <- Ref.in[I, F, Set[BinanceAsset]](Set.empty)
      service <- logs
        .forService[BinanceSyncService[F]]
        .map(log =>
          // The clock is passed on explicitly, since the one Sync brings would be chosen over it.
          new BinanceSyncServiceImpl[F](
            account,
            config,
            api,
            records,
            pairs,
            prices,
            instruments,
            systemAlerts,
            Memory(cursors, lastScan, raised, counted, rebuilt)
          )(using log, Sync[F], clock)
        )
    } yield service
}
