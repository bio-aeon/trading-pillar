package st.btu.trading.pillar.services

import scala.concurrent.duration.FiniteDuration

/** What differs between deployments in how an account's Tinkoff operations are synced.
  *
  * @param initialYearsAgo
  *   how far back the first sync of an account with no stored operation reaches, in years
  * @param rereadWindow
  *   how far back every sync reads the account's operations again, so that an operation Tinkoff
  *   reports late, or changes after the sync passed it, is fetched and stored again
  */
final case class TcsPortfolioSyncConfig(initialYearsAgo: Int, rereadWindow: FiniteDuration)
