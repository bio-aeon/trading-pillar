package st.btu.trading.pillar.storages

import st.btu.trading.pillar.alerts.{AlertRuleId, NewPriceRule, PriceCrossed}
import st.btu.trading.pillar.models.domain.AccountId

/** Price-threshold alert rules. */
trait AlertRuleStorage[F[_]] {

  /** Every enabled price rule across every account. */
  def allEnabledPriceRules: F[List[PriceCrossed]]

  /** Creates a new price rule, indistinguishable once persisted from one authored elsewhere. */
  def insertPriceRule(rule: NewPriceRule): F[AlertRuleId]

  /** Deletes `id` if it belongs to `accountId`.
    *
    * @return
    *   whether a row was actually removed
    */
  def delete(accountId: AccountId, id: AlertRuleId): F[Boolean]

  /** This account's enabled price rules. */
  def byAccount(accountId: AccountId): F[List[PriceCrossed]]
}
