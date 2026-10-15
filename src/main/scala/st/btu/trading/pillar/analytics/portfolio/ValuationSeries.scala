package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.{AccountId, Currency}

/** An account's valuation over a date range, one point per day.
  *
  * @param currency
  *   the single base currency every point's value, invested and cash flow are expressed in
  *   (`ValuationConfig.baseCurrency`)
  * @param points
  *   one point per day from the first date to the last, oldest first; empty when the last date is
  *   before the first
  * @param unpricedInstruments
  *   tickers, or for a non-base cash balance with no FX rate its currency code (e.g. `USD`), that
  *   could not be priced or converted on at least one date in the series - no known close on or
  *   before that date, or no FX rate to convert into the base currency - and were therefore
  *   excluded from `value` on that date
  * @param unmappedFigis
  *   FIGIs seen on executed buy and sell operations that the `InstrumentDirectory` does not know,
  *   so no ticker could be resolved for them; their positions are excluded from `value` on every
  *   date
  */
final case class ValuationSeries(
  accountId: AccountId,
  currency: Currency,
  points: Vector[ValuationPoint],
  unpricedInstruments: Set[String],
  unmappedFigis: Set[String]
)
