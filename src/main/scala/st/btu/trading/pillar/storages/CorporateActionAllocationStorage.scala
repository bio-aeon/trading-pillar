package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.tax.CorporateActionAllocation

/** Operator-configured cost allocations of spin-offs, which the tax lots read. */
trait CorporateActionAllocationStorage[F[_]] {

  /** The allocations stored for `accountId`, at most one for each spin-off group and new
    * instrument, in no particular order.
    */
  def byAccount(accountId: AccountId): F[List[CorporateActionAllocation]]
}
