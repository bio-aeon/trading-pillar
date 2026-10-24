package st.btu.trading.pillar.gateways

import st.btu.trading.pillar.models.market.YahooSymbol

/** A Yahoo market data source that can look a ticker up under the symbol Yahoo knows it by. */
trait YahooSymbols[F[_]] {

  /** A source that asks Yahoo for `symbol`, or for the ticker it is given when `None`, and reports
    * every bar, quote, dividend and instrument description under the ticker it was given. Views of
    * one source share what it has already fetched.
    */
  def onSymbol(symbol: Option[YahooSymbol]): MarketDataSource[F]
}
