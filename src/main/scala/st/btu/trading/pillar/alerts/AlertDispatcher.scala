package st.btu.trading.pillar.alerts

import java.time.Instant

/** Runs an [[Alert]] through the guard chain (mute, cooldown, hysteresis, dedup, daily cap, quiet
  * hours) and, if none suppress it, delivers it to every named channel.
  */
trait AlertDispatcher[F[_]] {

  /** Dispatches `alert` under `policy` to `channels`, recording the outcome either way. */
  def dispatch(alert: Alert, policy: DeliveryPolicy, channels: List[String]): F[DispatchOutcome]

  /** Suppresses every alert, regardless of rule or policy, until `until`. */
  def mute(until: Instant): F[Unit]

  /** Clears a mute set by [[mute]]. */
  def unmute: F[Unit]
}

/** Result of one [[AlertDispatcher.dispatch]] call. */
sealed trait DispatchOutcome

object DispatchOutcome {

  /** Delivered; `channels` lists which ones, empty only when none were requested. */
  final case class Delivered(channels: List[String]) extends DispatchOutcome

  /** Suppressed by a guard, or by every channel's send failing; `reason` names which. */
  final case class Suppressed(reason: String) extends DispatchOutcome
}
