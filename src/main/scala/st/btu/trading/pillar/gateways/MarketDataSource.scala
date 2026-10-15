package st.btu.trading.pillar.gateways

import st.btu.trading.pillar.models.market.*

import java.time.LocalDate

/** A single broker or data provider's market data: bars, quotes, instrument metadata and dividends
  * for one [[st.btu.trading.pillar.models.market.Source]].
  */
trait MarketDataSource[F[_]] {

  /** The broker or provider this instance serves data from. */
  def source: Source

  /** The board this source looks tickers up on, for a source that tells boards apart; `None`
    * otherwise.
    */
  def board: Option[ClassCode]

  /** Daily bars for `ticker` between `since` and `until`, chronological.
    *
    * @return
    *   a failed `F` with `MarketDataError` if the ticker is unrecognized, the source is
    *   rate-limited, or the request otherwise fails
    */
  def dailyBars(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[Bar]]

  /** The latest quote for `ticker`, or `None` if there isn't one available right now. */
  def latestQuote(ticker: Ticker): F[Option[Quote]]

  /** Metadata for `ticker` (name, type, currency, exchange, ...), or `None` if unavailable. */
  def instrumentInfo(ticker: Ticker): F[Option[InstrumentInfo]]

  /** Dividend events for `ticker` between `since` and `until`.
    *
    * @return
    *   a failed `F` with `MarketDataError` under the same conditions as [[dailyBars]]
    */
  def dividends(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[DividendEvent]]
}
