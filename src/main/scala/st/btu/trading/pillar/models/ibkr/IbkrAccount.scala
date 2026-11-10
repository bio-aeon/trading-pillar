package st.btu.trading.pillar.models.ibkr

import st.btu.trading.pillar.models.domain.AccountId

/** An Interactive Brokers account the bot syncs.
  *
  * @param accountId
  *   the account the statement rows and their translations are stored under
  * @param ibkrAccountId
  *   the account at IB the statements are read for; a statement for any other account is refused
  * @param queryId
  *   the Activity Flex Query that reports the account's statements
  */
final case class IbkrAccount(
  accountId: AccountId,
  ibkrAccountId: IbkrAccountId,
  queryId: FlexQueryId
)
