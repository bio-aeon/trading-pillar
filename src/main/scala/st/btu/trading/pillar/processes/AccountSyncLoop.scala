package st.btu.trading.pillar.processes

import cats.effect.{Ref, Temporal}
import cats.syntax.flatMap.*
import fs2.Stream
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.data.{ProcessContext, TraceIds}
import st.btu.trading.pillar.models.domain.AccountId
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.Logging
import tofu.syntax.context.*

import scala.concurrent.duration.FiniteDuration

// The loop that syncs one account at a broker: it records the account's stored records again
// until that succeeds once, and syncs the account every `interval`. Each run takes a fresh trace
// id and runs in a `ProcessContext` that names the account. A crash raises the system alert
// `process-crashed:<name>:<account id>`, and the loop starts again after `interval`.
private[processes] object AccountSyncLoop {

  def restarting[F[_]: Temporal: Logging: GenUUID](
    name: String,
    accountId: AccountId,
    interval: FiniteDuration,
    systemAlerts: SystemAlerts[F]
  )(rebuild: F[Unit], sync: F[Unit])(implicit WL: WithLocal[F, ProcessContext]): Stream[F, Unit] =
    Stream.eval(Ref[F].of(false)).flatMap { rebuilt =>
      val rebuildOnce = rebuilt.get.ifM(Temporal[F].unit, rebuild >> rebuilt.set(true))
      val tracedRun = TraceIds
        .next[F]
        .flatMap(uuid => (rebuildOnce >> sync).local(_.copy(traceId = uuid, accountId = accountId)))
      ProcessLoop.restarting(s"$name:${accountId.value}", interval, systemAlerts)(tracedRun)
    }
}
