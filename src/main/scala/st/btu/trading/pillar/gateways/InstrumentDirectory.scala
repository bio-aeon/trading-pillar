package st.btu.trading.pillar.gateways

import st.btu.trading.pillar.models.domain.Figi
import st.btu.trading.pillar.models.market.BrokerInstrument

/** Resolves the FIGI on a broker operation to the instrument it identifies. */
trait InstrumentDirectory[F[_]] {

  /** The instrument identified by `figi`, or `None` if the broker does not know it.
    *
    * @return
    *   a failed `F` with `MarketDataError` if the lookup itself fails
    */
  def byFigi(figi: Figi): F[Option[BrokerInstrument]]
}
