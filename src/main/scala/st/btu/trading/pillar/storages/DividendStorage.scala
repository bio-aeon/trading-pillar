package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.market.{DividendEvent, Source, SourcedTicker, Ticker}

import java.time.LocalDate

/** Persisted dividend events. Events of different sources coexist, so reads name the source. */
trait DividendStorage[F[_]] {

  /** Inserts or updates `events`.
    *
    * @return
    *   the number of rows written
    */
  def upsert(events: List[DividendEvent]): F[Int]

  /** `ticker`'s dividend events from `source` with an ex-date between `from` and `to`. */
  def byTicker(
    ticker: Ticker,
    source: Source,
    from: LocalDate,
    to: LocalDate
  ): F[List[DividendEvent]]

  /** Dividend events with an ex-date on or after `from`, for any of `tickers` and only from the
    * source paired with that ticker.
    */
  def upcoming(tickers: List[SourcedTicker], from: LocalDate): F[List[DividendEvent]]
}
