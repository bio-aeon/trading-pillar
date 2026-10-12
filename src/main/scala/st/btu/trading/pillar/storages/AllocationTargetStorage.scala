package st.btu.trading.pillar.storages

import st.btu.trading.pillar.analytics.portfolio.AllocationTarget
import st.btu.trading.pillar.models.domain.AccountId

/** Operator-configured allocation targets. */
trait AllocationTargetStorage[F[_]] {

  /** This account's enabled targets, ordered by dimension then key - the order
    * `Rebalancer.drift`/`suggest` echo into their own result lists.
    */
  def byAccount(accountId: AccountId): F[List[AllocationTarget]]
}
