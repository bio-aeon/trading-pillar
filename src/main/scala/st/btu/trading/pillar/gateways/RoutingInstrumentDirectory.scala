package st.btu.trading.pillar.gateways

import cats.Applicative
import st.btu.trading.pillar.models.domain.{Broker, InstrumentKey}
import st.btu.trading.pillar.models.market.BrokerInstrument

/** An [[InstrumentDirectory]] that answers each key from the directory of the key's broker, and
  * with no instrument for a key of a broker it has no directory for.
  */
final class RoutingInstrumentDirectory[F[_]] private (
  directories: Map[Broker, InstrumentDirectory[F]]
)(implicit F: Applicative[F])
    extends InstrumentDirectory[F] {

  def byKey(key: InstrumentKey): F[Option[BrokerInstrument]] =
    directories.get(key.broker).fold(F.pure(Option.empty[BrokerInstrument]))(_.byKey(key))
}

object RoutingInstrumentDirectory {

  /** Wires a directory that routes each key to the entry of `directories` for its broker. */
  def create[F[_]: Applicative](
    directories: Map[Broker, InstrumentDirectory[F]]
  ): RoutingInstrumentDirectory[F] =
    new RoutingInstrumentDirectory[F](directories)
}
