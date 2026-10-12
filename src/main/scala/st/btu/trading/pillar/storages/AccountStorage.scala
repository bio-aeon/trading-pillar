package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.Account

/** Persisted trading accounts. */
trait AccountStorage[F[_]] {

  /** Every stored account. */
  def allAccounts: F[List[Account]]
}
