package st.btu.trading.pillar.alerts

import scala.concurrent.duration.*

/** Per-rule delivery guard configuration applied by [[AlertDispatcher]].
  *
  * @param cooldown
  *   minimum time between two delivered alerts of the same rule, ticker and kind, except strategy
  *   warnings, which the live strategy service paces itself; also how far back the dedup guard
  *   looks
  * @param hysteresisPct
  *   percentage; a repeat PreTarget/TargetHit alert is suppressed while the trigger value stays
  *   within this percentage of the last delivered one
  * @param maxPerDay
  *   suppresses further alerts for the same rule once this many have delivered today, when set
  */
final case class DeliveryPolicy(
  cooldown: FiniteDuration = DeliveryPolicy.DefaultCooldown,
  hysteresisPct: BigDecimal = DeliveryPolicy.DefaultHysteresisPct,
  maxPerDay: Option[Int] = None,
  quietHours: Option[QuietHours] = None
)

object DeliveryPolicy {
  val DefaultCooldown: FiniteDuration = 1.hour
  val DefaultHysteresisPct: BigDecimal = BigDecimal("0.5")
}
