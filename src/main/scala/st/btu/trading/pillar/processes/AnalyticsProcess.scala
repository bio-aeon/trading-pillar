package st.btu.trading.pillar.processes

import fs2.Stream

trait AnalyticsProcess[F[_]] {
  def run: Stream[F, Unit]
}
