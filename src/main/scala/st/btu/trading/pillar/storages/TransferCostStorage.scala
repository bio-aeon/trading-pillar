package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.tax.TransferCost

/** Operator-entered costs of the units transferred into an account, which the tax lots read. */
trait TransferCostStorage[F[_]] {

  /** The costs stored for `accountId`, at most one for each operation `ref`, in no particular
    * order.
    */
  def byAccount(accountId: AccountId): F[List[TransferCost]]
}
