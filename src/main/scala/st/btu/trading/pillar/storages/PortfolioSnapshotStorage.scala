package st.btu.trading.pillar.storages

import st.btu.trading.pillar.analytics.portfolio.PortfolioSnapshot
import st.btu.trading.pillar.models.domain.AccountId

/** Persisted daily portfolio snapshots. The library only writes them; the consumer reads them back,
  * for example to show on a dashboard.
  */
trait PortfolioSnapshotStorage[F[_]] {

  /** Stores `snapshot` as `accountId`'s snapshot for `snapshot.holdings.date`, replacing whatever
    * is stored for that account and date, positions included: all of it or, on failure, none.
    */
  def save(accountId: AccountId, snapshot: PortfolioSnapshot): F[Unit]
}
