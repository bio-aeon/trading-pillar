package su.wps.trading.pillar.services

import java.time.ZonedDateTime
import scala.concurrent.duration.FiniteDuration

trait TcsPortfolioService[F[_]] {
  def syncOperations(
    interval: FiniteDuration,
    overlap: FiniteDuration,
    lastDt: Option[ZonedDateTime] = None
  ): F[Option[ZonedDateTime]]
}
