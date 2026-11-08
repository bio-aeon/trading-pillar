package st.btu.trading.pillar.gateways

import st.btu.trading.pillar.models.domain.InstrumentKey
import st.btu.trading.pillar.models.market.BrokerInstrument

/** Resolves the instrument key on a broker operation to the instrument it identifies. */
trait InstrumentDirectory[F[_]] {

  /** The instrument identified by `key`, or `None` if its broker does not know it.
    *
    * @return
    *   a failed `F` with `MarketDataError` if the lookup itself fails
    */
  def byKey(key: InstrumentKey): F[Option[BrokerInstrument]]
}
