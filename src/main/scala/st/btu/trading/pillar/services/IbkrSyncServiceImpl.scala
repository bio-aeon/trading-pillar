package st.btu.trading.pillar.services

import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.traverse.*
import cats.{Functor, MonadThrow}
import st.btu.trading.pillar.data.UtcClock
import st.btu.trading.pillar.gateways.ibkr.{FlexError, FlexWebService, IbkrStatementParser}
import st.btu.trading.pillar.models.ibkr.*
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
  instruments: BrokerInstrumentStorage[F]
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends IbkrSyncService[F] {
  import IbkrSyncServiceImpl.*

  def sync: F[Unit] =
    for {
      today <- clock.realTimeInstant.map(LocalDate.ofInstant(_, config.statementZone))
      latest <- statements.latestReportDate(account.accountId)
      from = latest.fold(firstDay(today))(_.minusDays(config.rereadDays.toLong))
      to = today.minusDays(1)
      windows = windowsBetween(from, to, config.windowDays)
      _ <- info"Syncing the statements of Flex query ${account.queryId} for $from to $to"
      _ <- windows.traverse_(syncWindow)
    } yield ()

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
      records <- translated(rows, IbkrRowTranslation.canceledTransactions(rows))
      _ <- recordAll(records)
    } yield ()

  // Reads `window`. One that IB cannot generate is read as two halves, down to a single day, whose
  // failure is final.
  private def syncWindow(window: Window): F[Unit] =
    readWindow(window).recoverWith {
      case error @ FlexError.RetriesExhausted(FlexError.StatementNotGenerated, _, _) =>
        window.halves.fold(F.raiseError[Unit](error)) { case (first, second) =>
          warn"IB could not generate the statement for ${window.from} to ${window.to}, reading halves" >>
            syncWindow(first) >> syncWindow(second)
        }
    }

  private def readWindow(window: Window): F[Unit] =
    for {
      xml <- flex.statement(account.queryId, window.from, window.to)
      statement <- ownStatement(xml)
      rows = distinctRows(statement.rows.map(_.attributedTo(account.accountId)))
      _ <- recordWithCancellations(rows)
      _ <- recordInstruments(statement)
      _ <- info"Recorded ${rows.size} statement rows for ${window.from} to ${window.to}"
    } yield ()

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

  // Records `rows` with their translations. A trade that a cancelling row among them names is
  // cancelled in its own translation, so one the statement does not repeat is loaded from the
  // storage and recorded again.
  private def recordWithCancellations(rows: List[IbkrStatementRow]): F[Unit] = {
    val canceled = IbkrRowTranslation.canceledTransactions(rows)
    val repeated = rows.filter(_.section == IbkrSection.Trade).map(_.transactionId).toSet
    val absent = canceled -- repeated
    for {
      originals <-
        if (absent.isEmpty) F.pure(Vector.empty[IbkrStatementRow])
        else statements.rowsByTransactionIds(account.accountId, IbkrSection.Trade, absent)
      records <- translated(rows ++ originals, canceled)
      _ <- recordAll(records)
    } yield ()
  }

  private def translated(
    rows: List[IbkrStatementRow],
    canceled: Set[IbkrTransactionId]
  ): F[List[IbkrRowRecord]] =
    rows.traverse(IbkrRowTranslation.recordOf(_, config.statementZone, canceled).liftTo[F])

  private def recordAll(records: List[IbkrRowRecord]): F[Unit] =
    records.grouped(RecordBatchSize).toList.traverse_(statements.record)

  private def recordInstruments(statement: IbkrStatement): F[Unit] =
    UtcClock
      .nowUtc[F]
      .flatMap(now => IbkrInstruments.of(statement, now).traverse_(instruments.upsert))
}

object IbkrSyncServiceImpl {

  // How many rows a storage call records.
  private val RecordBatchSize = 500

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

  /** Wires an [[IbkrSyncService]] that syncs `account` from `flex` into `statements`, and records
    * the instruments the statements describe in `instruments`.
    */
  def create[I[_]: Functor, F[_]: MonadThrow: Clock](
    account: IbkrAccount,
    config: IbkrSyncConfig,
    flex: FlexWebService[F],
    statements: IbkrStatementStorage[F],
    instruments: BrokerInstrumentStorage[F]
  )(implicit logs: Logs[I, F]): I[IbkrSyncService[F]] =
    logs
      .forService[IbkrSyncService[F]]
      .map(implicit log =>
        new IbkrSyncServiceImpl[F](account, config, flex, statements, instruments)
      )
}
