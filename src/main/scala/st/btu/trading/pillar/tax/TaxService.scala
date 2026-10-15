package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker

/** Estimates personal income tax from an account's FIFO-matched trade history. This is a reference
  * estimate, not tax advice.
  */
trait TaxService[F[_]] {

  /** The config this instance reports with, exposed so a caller can derive its own display-only
    * figures (e.g. the long-term holding-period threshold) without this trait growing a method per
    * such figure.
    */
  def config: TaxConfig

  /** `accountId`'s estimated tax picture for `year`. */
  def annualReport(accountId: AccountId, year: Int): F[AnnualTaxReport]

  /** `ticker`'s open FIFO lots for `accountId`, oldest first, across every figi the ticker has (one
    * per board); empty if there is no open position.
    */
  def openLots(accountId: AccountId, ticker: Ticker): F[List[Lot]]
}
