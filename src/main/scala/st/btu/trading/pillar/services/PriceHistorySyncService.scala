package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.market.{Source, Ticker, WatchlistItem}

import java.time.LocalDate

/** Keeps one ticker's stored price history current. */
trait PriceHistorySyncService[F[_]] {

  /** Syncs `item`'s bars and dividends as of `today` from the source its ticker is configured to
    * use: backfills from scratch if nothing is stored yet or the stored metadata was read from
    * another source or board, otherwise incrementally since the last stored bar. When that source
    * has no adapter configured nothing is fetched and the report is empty.
    */
  def syncSymbol(item: WatchlistItem, today: LocalDate): F[SyncReport]
}

/** The outcome of one [[PriceHistorySyncService.syncSymbol]] call.
  *
  * @param source
  *   the source the ticker is configured to use
  * @param fetched
  *   bars fetched from the market data source
  * @param upserted
  *   bars actually written (fetched bars may overlap already-stored ones)
  */
final case class SyncReport(
  ticker: Ticker,
  source: Source,
  fetched: Int,
  upserted: Int,
  dividends: Int,
  since: LocalDate,
  until: LocalDate
)
