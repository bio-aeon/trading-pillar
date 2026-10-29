package st.btu.trading.pillar.gateways

import st.btu.trading.pillar.models.market.*

import java.time.LocalDate

/** A single broker or data provider's market data: bars, quotes, instrument metadata and dividends
  * for one [[st.btu.trading.pillar.models.market.Source]].
  */
trait MarketDataSource[F[_]] {

  /** The setting this instance serves data under: its provider, and the Yahoo symbol or Tinkoff
    * board it looks tickers up by. A Tinkoff source always reports its board, never an unresolved
    * default.
    */
  def origin: SourceSetting

  /** Daily bars for `ticker` between `since` and `until`, chronological, dated in the exchange time
    * zone that [[instrumentInfo]] reports. Only sessions that have closed are returned, so every
    * bar is final.
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
