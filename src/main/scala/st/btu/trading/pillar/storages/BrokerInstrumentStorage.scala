package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.Figi
import st.btu.trading.pillar.models.market.BrokerInstrument

/** Broker instrument records, keyed by FIGI, kept so that a FIGI the broker knows is fetched from
  * it only once.
  */
trait BrokerInstrumentStorage[F[_]] {

  /** The stored record for `figi`, or `None` if there is none. */
  def find(figi: Figi): F[Option[BrokerInstrument]]

  /** Inserts `instrument`, or replaces the record stored for its FIGI. */
  def upsert(instrument: BrokerInstrument): F[Unit]
}
