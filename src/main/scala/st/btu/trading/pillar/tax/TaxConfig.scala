package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.Ticker

/** Tax-estimate parameters. Defaults mirror the Russian personal income tax regime: a progressive
  * 13%/15% rate above a RUB 2,400,000 threshold, and a long-term-ownership exemption for gains on
  * lots held `longTermYears` (365-day years) or more. Every field is overridable per deployment.
  * This produces an estimate, not tax advice.
  *
  * @param fxTickers
  *   per currency, the ticker whose bars carry that currency's rate when it is not the conventional
  *   `<CCY><baseCurrency>=X` pair; the bars are read from the source that ticker is configured to
  *   use
  */
final case class TaxConfig(
  baseCurrency: Currency = Currency("RUB"),
  progressiveThreshold: BigDecimal = BigDecimal(2400000),
  lowerRate: BigDecimal = BigDecimal("0.13"),
  upperRate: BigDecimal = BigDecimal("0.15"),
  longTermYears: Int = 3,
  fxTickers: Map[Currency, Ticker] = Map.empty
)
