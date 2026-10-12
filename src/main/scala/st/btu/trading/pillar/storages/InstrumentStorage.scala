package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.market.{InstrumentInfo, Ticker}

/** Instrument metadata, keyed by ticker and cross-referenced by FIGI. */
trait InstrumentStorage[F[_]] {

  /** `ticker`'s stored metadata, or `None` if there isn't any. */
  def find(ticker: Ticker): F[Option[InstrumentInfo]]

  /** Looks up by the optional Tinkoff cross-reference (`InstrumentInfo.figi`), not the primary key.
    * Used to resolve a Tinkoff operation's FIGI back to a ticker and currency.
    */
  def findByFigi(figi: String): F[Option[InstrumentInfo]]

  /** Inserts or updates `info`. */
  def upsert(info: InstrumentInfo): F[Unit]
}
