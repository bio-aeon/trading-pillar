package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.market.{InstrumentInfo, Ticker}

/** Instrument metadata, keyed by ticker. */
trait InstrumentStorage[F[_]] {

  /** `ticker`'s stored metadata, or `None` if there isn't any. */
  def find(ticker: Ticker): F[Option[InstrumentInfo]]

  /** Inserts or updates `info`. */
  def upsert(info: InstrumentInfo): F[Unit]
}
