package st.btu.trading.pillar.tax

/** Tax-estimate parameters. Defaults mirror the Russian personal income tax regime: a progressive
  * 13%/15% rate above a RUB 2,400,000 threshold, and a long-term-ownership exemption for gains on
  * lots held `longTermYears` (365-day years) or more. Every field is overridable per deployment.
  * This produces an estimate, not tax advice.
  */
final case class TaxConfig(
  baseCurrency: String = "RUB",
  progressiveThreshold: BigDecimal = BigDecimal(2400000),
  lowerRate: BigDecimal = BigDecimal("0.13"),
  upperRate: BigDecimal = BigDecimal("0.15"),
  longTermYears: Int = 3
)
