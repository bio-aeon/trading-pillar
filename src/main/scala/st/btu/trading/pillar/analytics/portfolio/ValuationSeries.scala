package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.AccountId

// `currency` is the single base currency every point's value/invested/cashFlow is expressed in
// (PortfolioValuationServiceImpl.ValuationConfig.baseCurrency).
//
// `unpricedInstruments`: tickers (or, for a non-base cash balance with no FX rate, the raw
// currency code, e.g. "USD") that could not be priced/converted on at least one date in the
// series - no known close on or before that date, or no FX rate to convert into the base
// currency - and were therefore excluded from `value` on that date.
//
// `unmappedFigis`: FIGIs seen on executed buy/sell operations that have no
// InstrumentStorage.findByFigi mapping at all, so no ticker/currency could even be resolved for
// them; their positions are excluded from `value` on every date.
final case class ValuationSeries(
  accountId: AccountId,
  currency: String,
  points: Vector[ValuationPoint],
  unpricedInstruments: Set[String],
  unmappedFigis: Set[String]
)
