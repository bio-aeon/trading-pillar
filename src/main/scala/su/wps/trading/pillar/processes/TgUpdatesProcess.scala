package su.wps.trading.pillar.processes

import fs2.Stream

trait TgUpdatesProcess[F[_]] {
  def run: Stream[F, Unit]
}
