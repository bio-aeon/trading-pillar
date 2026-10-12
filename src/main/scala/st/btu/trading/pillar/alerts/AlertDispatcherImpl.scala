package st.btu.trading.pillar.alerts

import cats.effect.{Async, Ref, Sync, Temporal}
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.storages.AlertHistoryStorage
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{Instant, ZonedDateTime}

// Guard order: mute -> cooldown per (ruleKey, symbol, kind) -> hysteresis for threshold rules
// (PreTarget/TargetHit, in-memory armed state) -> dedup hash within cooldown -> daily cap ->
// quiet hours. Every suppression is recorded too, with suppressedBy set to the guard's reason. A
// delivery that clears every guard but reaches no channel (channels non-empty, none succeeded)
// is recorded the same way, with suppressedBy = "delivery_failed" and no hysteresis update.
final class AlertDispatcherImpl[F[_]: Logging] private (
  channelsByName: Map[String, AlertChannel[F]],
  history: AlertHistoryStorage[F],
  muteUntilRef: Ref[F, Option[Instant]],
  // ruleKey -> triggerValue of the last delivered PreTarget/TargetHit alert for that rule.
  hysteresisRef: Ref[F, Map[String, BigDecimal]]
)(implicit F: Temporal[F])
    extends AlertDispatcher[F] {

  import AlertDispatcherImpl.Reason

  def mute(until: Instant): F[Unit] = muteUntilRef.set(Some(until))

  def unmute: F[Unit] = muteUntilRef.set(None)

  def dispatch(alert: Alert, policy: DeliveryPolicy, channels: List[String]): F[DispatchOutcome] =
    guard(alert, policy).flatMap {
      case Some(reason) => suppress(alert, reason)
      case None => deliver(alert, channels)
    }

  private def guard(alert: Alert, policy: DeliveryPolicy): F[Option[String]] =
    firstSuppression(
      List(
        checkMute(alert),
        checkCooldown(alert, policy),
        checkHysteresis(alert, policy),
        checkDedup(alert, policy),
        checkDailyCap(alert, policy),
        checkQuietHours(alert, policy)
      )
    )

  private def firstSuppression(checks: List[F[Option[String]]]): F[Option[String]] =
    checks match {
      case Nil => none[String].pure[F]
      case head :: tail =>
        head.flatMap {
          case None => firstSuppression(tail)
          case some => some.pure[F]
        }
    }

  private def checkMute(alert: Alert): F[Option[String]] =
    muteUntilRef.get.map(_.filter(alert.at.toInstant.isBefore).as(Reason.Mute))

  private def checkCooldown(alert: Alert, policy: DeliveryPolicy): F[Option[String]] =
    history.lastDelivered(alert.ruleKey, alert.symbol, alert.kind).map {
      case Some(last) if alert.at.isBefore(last.deliveredAt.plusNanos(policy.cooldown.toNanos)) =>
        Some(Reason.Cooldown)
      case _ => None
    }

  // Only PreTarget/TargetHit (threshold-rule) alerts re-arm on price movement; strategy alerts
  // are already gated by the strategy's own state machine (see LiveStrategyService).
  private def checkHysteresis(alert: Alert, policy: DeliveryPolicy): F[Option[String]] =
    if (!isThresholdKind(alert.kind)) none[String].pure[F]
    else
      alert.triggerValue.fold(none[String].pure[F]) { value =>
        hysteresisRef.get.map(_.get(alert.ruleKey).collect {
          case lastValue
              if lastValue != 0 && relativeDistance(
                value,
                lastValue
              ) < policy.hysteresisPct / 100 =>
            Reason.Hysteresis
        })
      }

  private def relativeDistance(a: BigDecimal, b: BigDecimal): BigDecimal = (a - b).abs / b.abs

  private def isThresholdKind(kind: AlertKind): Boolean =
    kind == AlertKind.PreTarget || kind == AlertKind.TargetHit

  private def checkDedup(alert: Alert, policy: DeliveryPolicy): F[Option[String]] =
    history
      .existsDedup(alert.dedupHash, alert.at.minusNanos(policy.cooldown.toNanos))
      .map(exists => if (exists) Some(Reason.Dedup) else None)

  private def checkDailyCap(alert: Alert, policy: DeliveryPolicy): F[Option[String]] =
    policy.maxPerDay match {
      case None => none[String].pure[F]
      case Some(max) =>
        val startOfDay = alert.at.toLocalDate.atStartOfDay(alert.at.getZone)
        history
          .deliveredCountSince(alert.ruleKey, startOfDay)
          .map(count => if (count >= max) Some(Reason.DailyCap) else None)
    }

  // AlertKind.System is the one kind meant to always get through regardless of quiet hours (e.g.
  // a sync-failed alert should not wait until morning); every other guard still applies to it.
  private def checkQuietHours(alert: Alert, policy: DeliveryPolicy): F[Option[String]] =
    if (alert.kind == AlertKind.System) none[String].pure[F]
    else policy.quietHours.filter(withinQuietHours(_, alert.at)).as(Reason.QuietHours).pure[F]

  private def withinQuietHours(quietHours: QuietHours, at: ZonedDateTime): Boolean = {
    val local = at.withZoneSameInstant(quietHours.zone).toLocalTime
    if (!quietHours.start.isAfter(quietHours.end))
      !local.isBefore(quietHours.start) && local.isBefore(quietHours.end)
    else
      !local.isBefore(quietHours.start) || local.isBefore(quietHours.end)
  }

  private def suppress(alert: Alert, reason: String): F[DispatchOutcome] =
    for {
      _ <- history.record(toRecord(alert, suppressedBy = Some(reason), channels = Nil))
      _ <- debug"Alert suppressed by $reason: ${alert.ruleKey} (${alert.kind.value})"
    } yield DispatchOutcome.Suppressed(reason)

  private def deliver(alert: Alert, channels: List[String]): F[DispatchOutcome] = {
    val attempted = channels.traverse(name => sendVia(name, alert).tupleLeft(name))
    attempted.flatMap { results =>
      val sent = results.collect { case (name, true) => name }
      if (channels.nonEmpty && sent.isEmpty) suppress(alert, Reason.DeliveryFailed)
      else recordDelivered(alert, sent)
    }
  }

  private def recordDelivered(alert: Alert, sent: List[String]): F[DispatchOutcome] =
    for {
      _ <- F.whenA(isThresholdKind(alert.kind) && alert.triggerValue.isDefined)(
        hysteresisRef.update(_.updated(alert.ruleKey, alert.triggerValue.get))
      )
      _ <- history.record(toRecord(alert, suppressedBy = None, channels = sent))
    } yield DispatchOutcome.Delivered(sent)

  private def sendVia(name: String, alert: Alert): F[Boolean] =
    channelsByName.get(name) match {
      case Some(channel) =>
        F.handleErrorWith(channel.send(alert).as(true))(e =>
          errorCause"Alert channel $name failed to send ${alert.ruleKey}" (e).as(false)
        )
      case None =>
        warn"Unknown alert channel: $name".as(false)
    }

  private def toRecord(
    alert: Alert,
    suppressedBy: Option[String],
    channels: List[String]
  ): AlertRecord =
    AlertRecord(
      alert.ruleKey,
      alert.symbol,
      alert.kind,
      alert.dedupHash,
      s"${alert.title}: ${alert.body}",
      alert.at,
      suppressedBy,
      channels
    )
}

object AlertDispatcherImpl {

  private[pillar] object Reason {
    val Mute = "mute"
    val Cooldown = "cooldown"
    val Hysteresis = "hysteresis"
    val Dedup = "dedup"
    val DailyCap = "daily_cap"
    val QuietHours = "quiet_hours"
    val DeliveryFailed = "delivery_failed"
  }

  /** Wires an [[AlertDispatcher]] backed by `channels` and `history`, with no mute set and no
    * hysteresis armed.
    */
  def create[I[_]: Sync, F[_]: Async](
    channels: List[AlertChannel[F]],
    history: AlertHistoryStorage[F]
  )(implicit logs: Logs[I, F]): I[AlertDispatcherImpl[F]] =
    for {
      muteUntilRef <- Ref.in[I, F, Option[Instant]](None)
      hysteresisRef <- Ref.in[I, F, Map[String, BigDecimal]](Map.empty)
      dispatcher <- logs
        .forService[AlertDispatcher[F]]
        .map(implicit log =>
          new AlertDispatcherImpl[F](
            channels.map(c => c.name -> c).toMap,
            history,
            muteUntilRef,
            hysteresisRef
          )
        )
    } yield dispatcher
}
