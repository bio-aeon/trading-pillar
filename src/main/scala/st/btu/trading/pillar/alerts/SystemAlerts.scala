package st.btu.trading.pillar.alerts

import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.{Applicative, Functor, Monad}
import st.btu.trading.pillar.data.UtcClock
import tofu.logging.{Logging, Logs}

/** Infrastructure-level alerts (a sync failure, a crashed process loop, ...), as opposed to the
  * per-symbol or per-strategy alerts raised directly through `AlertDispatcher`. `raise`'s rule key
  * is `"system:<name>"`, its kind is always `AlertKind.System`, and it is delivered through the
  * same dispatcher (so the usual guard chain still applies, minus quiet hours) to every channel the
  * caller configured.
  */
trait SystemAlerts[F[_]] {

  /** Raises a system alert named `name` with the given `title` and `body`. */
  def raise(name: String, title: String, body: String): F[Unit]
}

object SystemAlerts {

  /** A [[SystemAlerts]] that discards every call; for tests and optional wiring. */
  def noop[F[_]: Applicative]: SystemAlerts[F] = new SystemAlerts[F] {
    def raise(name: String, title: String, body: String): F[Unit] = Applicative[F].unit
  }
}

final class SystemAlertsImpl[F[_]: Logging] private (
  dispatcher: AlertDispatcher[F],
  channels: List[String],
  policy: DeliveryPolicy
)(implicit F: Monad[F], clock: Clock[F])
    extends SystemAlerts[F] {

  def raise(name: String, title: String, body: String): F[Unit] =
    UtcClock.nowUtc[F].flatMap { now =>
      val alert = Alert(s"system:$name", None, AlertKind.System, title, body, None, now)
      dispatcher.dispatch(alert, policy, channels).void
    }
}

object SystemAlertsImpl {

  /** Wires a [[SystemAlerts]] that delivers through `dispatcher` to `channels` under `policy`
    * (default: 1 hour cooldown, no quiet hours - irrelevant here since the dispatcher bypasses that
    * guard for `AlertKind.System` regardless).
    */
  def create[I[_]: Functor, F[_]: Monad: Clock](
    dispatcher: AlertDispatcher[F],
    channels: List[String],
    policy: DeliveryPolicy = DeliveryPolicy()
  )(implicit logs: Logs[I, F]): I[SystemAlerts[F]] =
    logs
      .forService[SystemAlerts[F]]
      .map(implicit log => new SystemAlertsImpl[F](dispatcher, channels, policy))
}
