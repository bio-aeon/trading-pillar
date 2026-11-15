package st.btu.trading.pillar.services

import cats.effect.{Clock, Ref, Sync}
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.traverse.*
import cats.MonadThrow
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.gateways.ibkr.{FlexError, FlexWebService, IbkrStatementParser}
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.services.IbkrCorporateActions.Notice
import st.btu.trading.pillar.storages.{BrokerInstrumentStorage, IbkrStatementStorage}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.LocalDate
import java.time.temporal.ChronoUnit

final class IbkrSyncServiceImpl[F[_]: Logging] private (
  account: IbkrAccount,
  config: IbkrSyncConfig,
  flex: FlexWebService[F],
  statements: IbkrStatementStorage[F],
  instruments: BrokerInstrumentStorage[F],
  systemAlerts: SystemAlerts[F],
  raised: Ref[F, Set[Notice]]
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends IbkrSyncService[F] {
  import IbkrSyncServiceImpl.*

  def sync: F[Option[IbkrOpenPositions]] =
    for {
      today <- clock.realTimeInstant.map(LocalDate.ofInstant(_, config.statementZone))
      latest <- statements.latestReportDate(account.accountId)
      from = latest.fold(firstDay(today))(_.minusDays(config.rereadDays.toLong))
      to = today.minusDays(1)
      windows = windowsBetween(from, to, config.windowDays)
      _ <- info"Syncing the statements of Flex query ${account.queryId} for $from to $to"
      reported <- windows.traverse(syncWindow)
    } yield reported.lastOption

  // The configured start of the history, or the earliest day IB keeps when none is set or the one
  // set is before it.
  private def firstDay(today: LocalDate): LocalDate = {
    val earliest = IbkrSyncConfig.earliestHistory(today)
    config.historyStart.fold(earliest)(start => if (start.isAfter(earliest)) start else earliest)
  }

  def rebuildTranslations: F[Unit] =
    for {
      stored <- statements.rows(account.accountId)
      _ <- info"Recording again the ${stored.size} stored statement rows with their translations"
      rows = inOrder(stored)
      translation <- translated(rows, IbkrRowTranslation.canceledTransactions(rows), Nil)
      _ <- recordAll(translation.records)
      _ <- announce(translation.notices)
    } yield ()

  // Reads `window`. One that IB cannot generate is read as two halves, down to a single day, whose
  // failure is final. Gives the open positions of the statement that ends the window.
  private def syncWindow(window: Window): F[IbkrOpenPositions] =
    readWindow(window).recoverWith {
      case error @ FlexError.RetriesExhausted(FlexError.StatementNotGenerated, _, _) =>
        window.halves.fold(F.raiseError[IbkrOpenPositions](error)) { case (first, second) =>
          warn"IB could not generate the statement for ${window.from} to ${window.to}, reading halves" >>
            syncWindow(first) >> syncWindow(second)
        }
    }

  private def readWindow(window: Window): F[IbkrOpenPositions] =
    for {
      xml <- flex.statement(account.queryId, window.from, window.to)
      statement <- ownStatement(xml)
      rows = distinctRows(statement.rows.map(_.attributedTo(account.accountId)))
      _ <- recordWithEarlierRows(rows)
      _ <- recordInstruments(statement)
      _ <- info"Recorded ${rows.size} statement rows for ${window.from} to ${window.to}"
    } yield IbkrOpenPositions(statement.toDate, config.statementZone, statement.openPositions)

  // The statement of the configured IB account: a response can hold the statements of several, and
  // one for another account than the configured one is not this account's.
  private def ownStatement(xml: String): F[IbkrStatement] =
    for {
      all <- IbkrStatementParser.parse(xml).liftTo[F]
      own <- all
        .find(_.ibkrAccountId == account.ibkrAccountId)
        .liftTo[F](
          IbkrStatementError.AccountNotInStatement(account.ibkrAccountId, all.map(_.ibkrAccountId))
        )
    } yield own

  // Records `rows` with their translations. What a row says about rows stored earlier is taken up
  // by recording those again with it: a trade that a cancelling row among them names is cancelled
  // in its own translation, so one the statement does not repeat is loaded from the storage; and
  // corporate action legs are interpreted together, so a window that has any records the stored
  // ones again with its own.
  private def recordWithEarlierRows(rows: List[IbkrStatementRow]): F[Unit] = {
    val canceled = IbkrRowTranslation.canceledTransactions(rows)
    val repeated = rows.filter(_.section == IbkrSection.Trade).map(_.transactionId).toSet
    val absent = canceled -- repeated
    for {
      originals <-
        if (absent.isEmpty) F.pure(Vector.empty[IbkrStatementRow])
        else statements.rowsByTransactionIds(account.accountId, IbkrSection.Trade, absent)
      actions <- earlierActions(rows)
      translation <- translated(rows ++ originals ++ actions.legs, canceled, actions.parents)
      _ <- recordAll(translation.records)
      _ <- announce(translation.notices)
    } yield ()
  }

  // What a window with corporate action legs needs besides its own rows: the legs stored earlier,
  // which an action of this window can add to, and the stored rows that may be the parents of its
  // spin-offs. The window's version of a leg stands in place of the stored one.
  private def earlierActions(rows: List[IbkrStatementRow]): F[EarlierActions] = {
    val own = rows.filter(_.section == IbkrSection.CorporateAction)
    if (own.isEmpty) F.pure(EarlierActions.Empty)
    else
      for {
        stored <- statements.rowsOfSection(account.accountId, IbkrSection.CorporateAction)
        ownIds = own.map(_.transactionId).toSet
        earlier = stored.filterNot(row => ownIds(row.transactionId)).toList
        isins = IbkrCorporateActions.isinsNamed(own ++ earlier)
        parents <-
          if (isins.isEmpty) F.pure(Vector.empty[IbkrStatementRow])
          else statements.rowsByIsin(account.accountId, isins)
      } yield EarlierActions(earlier, parents.toList)
  }

  private def translated(
    rows: List[IbkrStatementRow],
    canceled: Set[IbkrTransactionId],
    parents: List[IbkrStatementRow]
  ): F[IbkrRecords.Result] =
    IbkrRecords.of(rows, canceled, parents, config.statementZone).liftTo[F]

  private def recordAll(records: List[IbkrRowRecord]): F[Unit] =
    records.grouped(RecordBatchSize).toList.traverse_(statements.record)

  // Raising comes before remembering what was raised, so that an alert that fails to go out is
  // tried again at the next sync.
  private def announce(notices: List[Notice]): F[Unit] =
    for {
      known <- raised.get
      fresh = notices.filterNot(known)
      _ <- F.whenA(fresh.nonEmpty)(
        systemAlerts.raise(
          s"$CorporateActionsAlert:${account.accountId.value}",
          s"IB corporate actions need a look: account ${account.accountId.value}",
          fresh
            .map(IbkrCorporateActions.describe)
            .mkString(
              s"Corporate actions of account ${account.accountId.value} that the bot did not read " +
                "for certain:\n",
              "\n",
              ""
            )
        ) >> raised.update(_ ++ fresh)
      )
    } yield ()

  private def recordInstruments(statement: IbkrStatement): F[Unit] =
    UtcClock
      .nowUtc[F]
      .flatMap(now => IbkrInstruments.of(statement, now).traverse_(instruments.upsert))
}

object IbkrSyncServiceImpl {

  // How many rows a storage call records.
  private val RecordBatchSize = 500

  private val CorporateActionsAlert = "ibkr-corporate-actions"

  // The rows a window with corporate action legs reads besides its own.
  private final case class EarlierActions(
    legs: List[IbkrStatementRow],
    parents: List[IbkrStatementRow]
  )

  private object EarlierActions {
    val Empty: EarlierActions = EarlierActions(Nil, Nil)
  }

  /** The report dates of one statement to read, both ends included. */
  private[services] final case class Window(from: LocalDate, to: LocalDate) {

    def days: Long = ChronoUnit.DAYS.between(from, to) + 1

    /** The two parts the window falls into, the first of them the longer when the days are odd;
      * none for a window of a single day.
      */
    def halves: Option[(Window, Window)] =
      Option.when(days > 1) {
        val lastOfFirst = from.plusDays((days + 1) / 2 - 1)
        (Window(from, lastOfFirst), Window(lastOfFirst.plusDays(1), to))
      }
  }

  // Consecutive windows of `length` days from `from` to `to`, the last one cut short at `to`.
  private[services] def windowsBetween(from: LocalDate, to: LocalDate, length: Int): List[Window] =
    Iterator
      .iterate(from)(_.plusDays(length.toLong))
      .takeWhile(!_.isAfter(to))
      .map { start =>
        val end = start.plusDays(length.toLong - 1)
        Window(start, if (end.isAfter(to)) to else end)
      }
      .toList

  // Of rows that share a section and a transaction id, the last one stands: a storage call must not
  // record one row twice.
  private def distinctRows(rows: List[IbkrStatementRow]): List[IbkrStatementRow] =
    rows.reverse.distinctBy(row => (row.section, row.transactionId)).reverse

  private def inOrder(rows: Iterable[IbkrStatementRow]): List[IbkrStatementRow] =
    rows.toList.sortBy(row =>
      (row.reportDate.toEpochDay, row.section.value, row.transactionId.value)
    )

  /** Wires an [[IbkrSyncService]] that syncs `account` from `flex` into `statements`, records the
    * instruments the statements describe in `instruments`, and raises a system alert through
    * `systemAlerts` for the corporate actions it could not read for certain.
    *
    * The alert is `ibkr-corporate-actions:<account id>`. It names the action's id, type and date,
    * IB's description of it and what the sync did with it, and never the IB account. The notices
    * raised are kept in memory, so after a restart the ones that still stand are raised once more.
    */
  def create[I[_]: Sync, F[_]: Sync](
    account: IbkrAccount,
    config: IbkrSyncConfig,
    flex: FlexWebService[F],
    statements: IbkrStatementStorage[F],
    instruments: BrokerInstrumentStorage[F],
    systemAlerts: SystemAlerts[F]
  )(implicit logs: Logs[I, F], clock: Clock[F]): I[IbkrSyncService[F]] =
    for {
      raised <- Ref.in[I, F, Set[Notice]](Set.empty)
      service <- logs
        .forService[IbkrSyncService[F]]
        .map(log =>
          // The clock is passed on explicitly, since the one Sync brings would be chosen over it.
          new IbkrSyncServiceImpl[F](
            account,
            config,
            flex,
            statements,
            instruments,
            systemAlerts,
            raised
          )(using log, Sync[F], clock)
        )
    } yield service
}
