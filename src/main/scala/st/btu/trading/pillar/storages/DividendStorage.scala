package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.market.{DividendEvent, Ticker}

import java.time.LocalDate

/** Persisted dividend events. */
trait DividendStorage[F[_]] {

  /** Inserts or updates `events`.
    *
    * @return
    *   the number of rows written
    */
  def upsert(events: List[DividendEvent]): F[Int]

  /** `ticker`'s dividend events with an ex-date between `from` and `to`. */
  def byTicker(ticker: Ticker, from: LocalDate, to: LocalDate): F[List[DividendEvent]]

  /** Dividend events with an ex-date on or after `from`, across any of `tickers`. */
  def upcoming(tickers: List[Ticker], from: LocalDate): F[List[DividendEvent]]
}
