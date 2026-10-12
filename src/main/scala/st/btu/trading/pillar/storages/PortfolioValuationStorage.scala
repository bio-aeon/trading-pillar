package st.btu.trading.pillar.storages

import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.models.domain.AccountId

import java.time.LocalDate

/** Persisted daily portfolio valuation points. */
trait PortfolioValuationStorage[F[_]] {

  /** Upserts `points` keyed by (account, date).
    *
    * @return
    *   the number of points written
    */
  def upsert(accountId: AccountId, currency: String, points: Vector[ValuationPoint]): F[Int]

  /** `accountId`'s valuation points between `from` and `to`, chronological and inclusive of both
    * bounds.
    */
  def series(accountId: AccountId, from: LocalDate, to: LocalDate): F[Vector[ValuationPoint]]

  /** `accountId`'s most recent valuation point, or `None` if there isn't one. */
  def latest(accountId: AccountId): F[Option[ValuationPoint]]
}
