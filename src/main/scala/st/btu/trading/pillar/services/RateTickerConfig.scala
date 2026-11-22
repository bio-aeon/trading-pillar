package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.Ticker

/** The currencies the valuation and the tax estimate convert amounts into, and the tickers that
  * carry the rate of a currency into them: what is needed to put those tickers on the watchlists,
  * so that their bars are synced.
  *
  * @param baseCurrencies
  *   the currencies amounts are converted into, such as the base currency of the valuation and the
  *   base currency of the tax estimate
  * @param fxTickers
  *   the ticker that carries the rate of a currency, for a currency whose rate is not the
  *   conventional `<currency><base currency>=X` pair, as `ValuationConfig.fxTickers` names them
  */
final case class RateTickerConfig(
  baseCurrencies: Set[Currency],
  fxTickers: Map[Currency, Ticker] = Map.empty
)
