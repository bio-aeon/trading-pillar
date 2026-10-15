package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.Ticker

/** Settings of the portfolio valuation.
  *
  * @param baseCurrency
  *   the currency every `ValuationPoint` is expressed in; a non-base position or cash balance is
  *   converted with the latest close of its rate ticker, `<CCY><baseCurrency>=X` (e.g. `USDRUB=X`)
  *   by default
  * @param fxTickers
  *   per currency, the ticker to use as its rate ticker instead of that conventional one; the
  *   ticker's bars are read from the source the ticker itself is configured to use
  * @param priceLookback
  *   how many days before `from` bars are additionally fetched, so a position priced at `from` can
  *   still carry forward a close from just before the requested window
  */
final case class ValuationConfig(
  baseCurrency: Currency = Currency("RUB"),
  fxTickers: Map[Currency, Ticker] = Map.empty,
  priceLookback: Int = 10
)
