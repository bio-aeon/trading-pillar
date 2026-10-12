package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.market.Source

// baseCurrency: every ValuationPoint is expressed in this currency; a non-base position or cash
// balance is converted with the latest "<CCY><baseCurrency>=X" close (e.g. "USDRUB=X").
// fxSource: the PriceHistoryStorage source those FX bars are read from.
// priceLookback: how many days before `from` bars are additionally fetched, so a position priced
// at `from` can still carry forward a close from just before the requested window.
final case class ValuationConfig(
  baseCurrency: String = "RUB",
  fxSource: Source = Source.Yahoo,
  priceLookback: Int = 10
)
