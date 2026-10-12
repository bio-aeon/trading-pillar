package st.btu.trading.pillar.alerts

import fs2.Stream

trait AlertProcess[F[_]] {
  def run: Stream[F, Unit]
}
