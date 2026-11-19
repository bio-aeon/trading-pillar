package st.btu.trading.pillar.gateways

import cats.Applicative
import st.btu.trading.pillar.models.domain.{Broker, InstrumentKey}
import st.btu.trading.pillar.models.market.BrokerInstrument
import st.btu.trading.pillar.storages.BrokerInstrumentStorage

/** An [[InstrumentDirectory]] for a broker whose API has no lookup of an instrument by the id it
  * gives it, such as Binance with its asset codes. The directory answers from the storage alone,
  * which the broker's sync fills with the instruments it reads of. A key of the broker that the
  * storage lacks, and a key of any other broker, are answered with no instrument.
  */
final class StoredInstrumentDirectory[F[_]] private (
  broker: Broker,
  storage: BrokerInstrumentStorage[F]
)(implicit F: Applicative[F])
    extends InstrumentDirectory[F] {

  def byKey(key: InstrumentKey): F[Option[BrokerInstrument]] =
    if (key.broker == broker) storage.find(key) else F.pure(None)
}

object StoredInstrumentDirectory {

  /** Wires an [[InstrumentDirectory]] over the instruments of `broker` in `storage`. */
  def create[F[_]: Applicative](
    broker: Broker,
    storage: BrokerInstrumentStorage[F]
  ): StoredInstrumentDirectory[F] =
    new StoredInstrumentDirectory[F](broker, storage)
}
