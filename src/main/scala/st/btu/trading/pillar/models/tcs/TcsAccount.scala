package st.btu.trading.pillar.models.tcs

import st.btu.trading.pillar.models.domain.AccountId

/** A Tinkoff brokerage account the bot syncs: the account its operations are stored under, and the
  * Tinkoff account they are read from.
  */
final case class TcsAccount(accountId: AccountId, brokerAccountId: BrokerAccountId)
