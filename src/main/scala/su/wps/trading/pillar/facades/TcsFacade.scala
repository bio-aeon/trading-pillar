package su.wps.trading.pillar.facades

import ru.tinkoff.piapi.contract.v1.operations.Operation
import su.wps.trading.pillar.models.domain.Portfolio

import java.time.ZonedDateTime

trait TcsFacade[F[_]] {
  def portfolio: F[Portfolio]

  def operations(since: ZonedDateTime, until: ZonedDateTime): F[List[Operation]]
}

object TcsFacade {
  val ApiVersion = "24 Jan 2023 00:53:14 +0300"
}
