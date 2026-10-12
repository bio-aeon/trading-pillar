package st.btu.trading.pillar.paper

import st.btu.trading.pillar.models.domain.AccountId

/** Virtual cash balance for an account's paper-mode strategy bindings. The row is authored and
  * topped up by the operator outside this library; trading-pillar only reads `cash` and updates it
  * after a fill.
  */
final case class PaperAccount(accountId: AccountId, cash: BigDecimal, currency: String)
