package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.{AccountId, TcsPortfolioOperation}

import java.time.ZonedDateTime

/** Persisted Tinkoff portfolio operations. */
trait TcsPortfolioOperationStorage[F[_]] {

  /** `accountId`'s most recently recorded operation, or `None` if there isn't one. */
  def lastOperation(accountId: AccountId): F[Option[TcsPortfolioOperation]]

  /** `accountId`'s operations between `since` and `until`. */
  def operationsByDtRange(
    accountId: AccountId,
    since: ZonedDateTime,
    until: ZonedDateTime
  ): F[Set[TcsPortfolioOperation]]

  /** Inserts `operations`, skipping any already on record. */
  def saveOperations(operations: List[TcsPortfolioOperation]): F[Unit]
}
