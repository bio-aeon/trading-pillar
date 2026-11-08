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
  * @param taxesWithheld
  *   minus the sum of the year's tax amounts: positive when the broker withheld tax on balance, and
  *   lowered by any refund or correction in the account's favour
  * @param estimatedTax
  *   the configured progressive rate applied to `taxableGains` only; dividend and coupon income is
  *   shown for reference, not re-estimated here
  * @param unconverted
  *   how many individual amounts (gains, dividends, coupons, tax operations) could not be converted
  *   into `currency` and were excluded from every sum above
  */
final case class AnnualTaxReport(
  year: Int,
  realizedGains: BigDecimal,
  exemptGains: Option[BigDecimal],
  taxableGains: BigDecimal,
  dividendIncome: BigDecimal,
  couponIncome: BigDecimal,
  taxesWithheld: BigDecimal,
  estimatedTax: BigDecimal,
  unconverted: Int,
  currency: Currency
)
