package st.btu.trading.pillar.processes

import fs2.Stream

trait TcsPortfolioSyncProcess[F[_]] {
  def run: Stream[F, Unit]
}
