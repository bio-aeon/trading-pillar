package st.btu.trading.pillar.models.binance

import st.btu.trading.pillar.models.domain.AccountId

/** A Binance account the bot syncs, read with a key that can read and do nothing else.
  *
  * @param accountId
  *   the account the records and their translations are stored under
  * @param apiKey
  *   the key of the account's read-only API key
  * @param secretKey
  *   the secret that signs the requests made with the key
  */
final case class BinanceAccount(accountId: AccountId, apiKey: ApiKey, secretKey: SecretKey) {

  // The default would print both keys, which are plain strings at runtime.
  override def toString: String = s"BinanceAccount(${accountId.value},***,***)"
}
