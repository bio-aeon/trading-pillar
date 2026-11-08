package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.InstrumentKey
import st.btu.trading.pillar.models.market.BrokerInstrument

/** Broker instrument records, keyed by the broker and the id that broker gives the instrument, kept
  * so that an instrument a broker knows is fetched from it only once.
  */
trait BrokerInstrumentStorage[F[_]] {

  /** The stored record for `instrument`, or `None` if there is none. */
  def find(instrument: InstrumentKey): F[Option[BrokerInstrument]]

  /** Inserts `instrument`, or replaces the record stored for its key. */
  def upsert(instrument: BrokerInstrument): F[Unit]
}
