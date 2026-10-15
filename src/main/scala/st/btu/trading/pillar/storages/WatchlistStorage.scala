package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{Ticker, WatchlistItem}

/** Per-account tickers tracked for price history sync and alerts. */
trait WatchlistStorage[F[_]] {

  /** Every enabled watchlist item across every account. */
  def allEnabled: F[List[WatchlistItem]]

  /** This account's watchlist items. */
  def byAccount(accountId: AccountId): F[List[WatchlistItem]]

  /** Adds `item` to its account's watchlist. */
  def add(item: WatchlistItem): F[Unit]

  /** Removes `ticker` from `accountId`'s watchlist.
    *
    * @return
    *   whether a row was actually removed
    */
  def remove(accountId: AccountId, ticker: Ticker): F[Boolean]
}
