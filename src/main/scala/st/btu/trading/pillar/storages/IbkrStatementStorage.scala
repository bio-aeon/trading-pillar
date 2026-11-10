package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.ibkr.{
  IbkrRowRecord,
  IbkrSection,
  IbkrStatementRow,
  IbkrTransactionId
}

import java.time.LocalDate

/** Persisted rows of Activity Flex Query statements as IB reported them, each recorded together
  * with its translation into [[st.btu.trading.pillar.models.domain.AccountOperation]]s.
  */
trait IbkrStatementStorage[F[_]] {

  /** Stores each record. A row not stored yet is inserted. One stored already, under the same
    * account, section and transaction id, has its report date and attributes replaced, so a row IB
    * reports again overwrites the earlier one.
    *
    * The row's translations are replaced as a whole. They are the operations of the account whose
    * `ref` is the row's `ref` (`IbkrStatementRow.ref`, such as `trade/123456`), or the row's `ref`
    * followed by a slash and a suffix (`trade/123456/commission`). The record's translations are
    * stored, each keyed by its account and `ref`, and the stored translations of the row that are
    * not among them are removed, so that one the row no longer produces does not stay. Match the
    * prefix literally and not as a pattern: a section's value can contain an underscore, which a
    * pattern reads as a wildcard.
    *
    * A row and its translations are stored, and the stale ones removed, in the same transaction, so
    * a failure leaves none of it done.
    */
  def record(records: List[IbkrRowRecord]): F[Unit]

  /** The latest report date among the rows stored for `accountId`, or `None` if there is none. */
  def latestReportDate(accountId: AccountId): F[Option[LocalDate]]

  /** Every row stored for `accountId`, in no particular order. */
  def rows(accountId: AccountId): F[Vector[IbkrStatementRow]]

  /** The rows of `section` stored for `accountId` under one of `ids`, in no particular order. */
  def rowsByTransactionIds(
    accountId: AccountId,
    section: IbkrSection,
    ids: Set[IbkrTransactionId]
  ): F[Vector[IbkrStatementRow]]
}
