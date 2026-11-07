package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.{AccountId, AccountOperation}

import java.time.Instant

/** Persisted account operations in terms shared by every broker.
  *
  * Read-only: each broker's own storage records its operations together with their translations
  * (see [[TcsPortfolioOperationStorage]]), so every stored operation names the broker's record it
  * came from through its `ref`.
  */
trait AccountOperationStorage[F[_]] {

  /** `accountId`'s operations that occurred from `since` (inclusive) until `until` (exclusive),
    * oldest first. Operations at the same instant come in `ref` order.
    */
  def operations(accountId: AccountId, since: Instant, until: Instant): F[Vector[AccountOperation]]
}
