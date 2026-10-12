package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.market.{Bar, Source, Ticker}

import java.time.LocalDate

/** Persisted daily OHLCV bars. */
trait PriceHistoryStorage[F[_]] {

  /** The date of `ticker`'s most recent stored bar from `source`, or `None` if there isn't one. */
  def lastBarDate(ticker: Ticker, source: Source): F[Option[LocalDate]]

  /** `ticker`'s bars from `source` between `from` and `to`. */
  def bars(ticker: Ticker, source: Source, from: LocalDate, to: LocalDate): F[List[Bar]]

  /** The most recent `limit` bars for `ticker` from `source`, chronological. */
  def latestBars(ticker: Ticker, source: Source, limit: Int): F[List[Bar]]

  /** Inserts or updates `bars`.
    *
    * @return
    *   the number of rows written
    */
  def upsertBars(bars: List[Bar]): F[Int]
}
