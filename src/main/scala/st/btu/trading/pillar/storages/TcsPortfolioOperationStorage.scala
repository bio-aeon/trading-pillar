package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.{AccountId, TcsOperationRecord, TcsPortfolioOperation}

import java.time.ZonedDateTime

/** Persisted Tinkoff portfolio operations as received, each recorded together with its translation
  * into an [[st.btu.trading.pillar.models.domain.AccountOperation]].
  */
trait TcsPortfolioOperationStorage[F[_]] {

  /** `accountId`'s most recently recorded operation, or `None` if there isn't one. */
  def lastOperation(accountId: AccountId): F[Option[TcsPortfolioOperation]]

  /** `accountId`'s operations between `since` and `until`. */
  def operationsByDtRange(
    accountId: AccountId,
    since: ZonedDateTime,
    until: ZonedDateTime
  ): F[Set[TcsPortfolioOperation]]

  /** Stores each record. An operation not stored yet is inserted. One stored already, under the
    * same account, `extId` and `operationType`, keeps its id and has every other field replaced, so
    * a later state of an operation overwrites the earlier one. The translation is stored the same
    * way, keyed by its account and `ref`.
    *
    * An operation and its translation are stored in the same transaction, so a failure leaves
    * neither of them stored.
    */
  def record(records: List[TcsOperationRecord]): F[Unit]
}
