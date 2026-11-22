package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.Currency

/** One account's estimated annual tax picture; every monetary field is expressed in `currency`.
  *
  * @param realizedGains
  *   every realized gain or loss of `year`, converted and summed
  * @param exemptGains
  *   the subset of `realizedGains` that is both long-term and itself positive, or `None` under a
  *   regime without a long-term exemption; a long-held loss stays in `realizedGains`, it is not
  *   "exempt"
  * @param taxableGains
  *   `realizedGains` less `exemptGains`, never below 0
  * @param dividendIncome
  *   the year's dividends, and the market value of the shares received as stock dividends, which
  *   the broker reports as a price per unit
  * @param rewardIncome
  *   the year's rewards, each converted on its day and summed: units received at their market value
  *   per unit, and cash received with no instrument as it is. Units whose price is not known count
  *   nothing, and are not an unconverted amount
  * @param taxesWithheld
  *   minus the sum of the year's tax amounts: positive when the broker withheld tax on balance, and
  *   lowered by any refund or correction in the account's favour
  * @param estimatedTax
  *   the configured progressive rate applied to `taxableGains` only; dividend, coupon and reward
  *   income is shown for reference, not re-estimated here
  * @param unconverted
  *   how many individual amounts (gains, dividends, stock dividends, coupons, rewards, tax
  *   operations) could not be converted into `currency` and were excluded from every sum above
  */
final case class AnnualTaxReport(
  year: Int,
  realizedGains: BigDecimal,
  exemptGains: Option[BigDecimal],
  taxableGains: BigDecimal,
  dividendIncome: BigDecimal,
  couponIncome: BigDecimal,
  rewardIncome: BigDecimal,
  taxesWithheld: BigDecimal,
  estimatedTax: BigDecimal,
  unconverted: Int,
  currency: Currency
)
