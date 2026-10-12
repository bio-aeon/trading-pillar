package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.AccountId

import java.time.LocalDate

trait PortfolioValuationService[F[_]] {

  // One ValuationPoint per calendar day in [from, to] (inclusive both ends; empty when
  // to.isBefore(from)).
  def dailySeries(accountId: AccountId, from: LocalDate, to: LocalDate): F[ValuationSeries]

  // A point-in-time snapshot of every open position as of `date` (inclusive), reusing the same
  // position/pricing/FX rules dailySeries applies for that one date - see
  // PortfolioValuationServiceImpl.
  def positionsAsOf(accountId: AccountId, date: LocalDate): F[PositionSnapshot]
}
