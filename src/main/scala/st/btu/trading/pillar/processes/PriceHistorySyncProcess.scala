package st.btu.trading.pillar.processes

import fs2.Stream

trait PriceHistorySyncProcess[F[_]] {
  def run: Stream[F, Unit]
}
