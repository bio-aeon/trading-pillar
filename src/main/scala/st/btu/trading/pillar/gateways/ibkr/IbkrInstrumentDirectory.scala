package st.btu.trading.pillar.gateways.ibkr

import cats.Applicative
import st.btu.trading.pillar.gateways.InstrumentDirectory
import st.btu.trading.pillar.models.domain.{Broker, InstrumentKey}
import st.btu.trading.pillar.models.market.BrokerInstrument
import st.btu.trading.pillar.storages.BrokerInstrumentStorage

/** An [[InstrumentDirectory]] for Interactive Brokers, whose instrument ids are contract ids. The
  * Flex Web Service has no lookup call, so the directory answers from the storage alone, which the
  * statement sync fills from the instruments each statement describes. A contract id the sync has
  * not seen, and a key of another broker, are answered with no instrument.
  */
final class IbkrInstrumentDirectory[F[_]] private (storage: BrokerInstrumentStorage[F])(implicit
  F: Applicative[F]
) extends InstrumentDirectory[F] {

  def byKey(key: InstrumentKey): F[Option[BrokerInstrument]] =
    key.broker match {
      case Broker.Ibkr => storage.find(key)
      case Broker.Tinkoff => F.pure(None)
    }
}

object IbkrInstrumentDirectory {

  /** Wires an [[InstrumentDirectory]] over the instruments in `storage`. */
  def create[F[_]: Applicative](storage: BrokerInstrumentStorage[F]): IbkrInstrumentDirectory[F] =
    new IbkrInstrumentDirectory[F](storage)
}
