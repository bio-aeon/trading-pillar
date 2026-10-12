package st.btu.trading.pillar.storages

import st.btu.trading.pillar.alerts.StrategyBinding
import st.btu.trading.pillar.models.domain.AccountId

/** Live strategy-to-ticker bindings. */
trait StrategyBindingStorage[F[_]] {

  /** Every enabled binding across every account. */
  def allEnabled: F[List[StrategyBinding]]

  /** This account's bindings. */
  def byAccount(accountId: AccountId): F[List[StrategyBinding]]
}
