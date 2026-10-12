package st.btu.trading.pillar.services.analysis

import st.btu.trading.pillar.models.market.Ticker

/** Combines a ticker's quote, instrument info, stored bars and dividends into one analysis. */
trait AnalysisService[F[_]] {

  /** Analyzes `ticker`.
    *
    * @return
    *   `StockAnalysis.empty(ticker)` if the ticker is not recognized by its routed market data
    *   source, rather than raising; every other failure propagates
    */
  def analyze(ticker: Ticker): F[StockAnalysis]
}
