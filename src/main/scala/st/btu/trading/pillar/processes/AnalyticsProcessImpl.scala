package st.btu.trading.pillar.processes

import cats.Functor
import cats.effect.Temporal
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import fs2.Stream
import st.btu.trading.pillar.alerts.*
import st.btu.trading.pillar.analytics.portfolio.{
  Allocation,
  Drift,
  PortfolioValuationService,
  Rebalancer
}
import st.btu.trading.pillar.data.{ProcessContext, TraceIds, UtcClock}
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.storages.{
  AccountStorage,
  AllocationTargetStorage,
  PortfolioValuationStorage
}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}
import tofu.syntax.context.*
import tofu.syntax.logging.*

import java.time.{LocalDate, ZonedDateTime}
import scala.concurrent.duration.*
import scala.math.BigDecimal.RoundingMode

final class AnalyticsProcessImpl[F[_]: Temporal: Logging: GenUUID](
  accounts: AccountStorage[F],
  valuationService: PortfolioValuationService[F],
  valuations: PortfolioValuationStorage[F],
  targets: AllocationTargetStorage[F],
  dispatcher: AlertDispatcher[F],
  systemAlerts: SystemAlerts[F],
  config: AnalyticsConfig,
  // Channel names every AllocationDrift alert is dispatched to (the configured alert channels,
  // same list a consumer would pass to any other DeliveryPolicy-driven alert).
  channels: List[String]
)(implicit WL: WithLocal[F, ProcessContext])
    extends AnalyticsProcess[F] {

  def run: Stream[F, Unit] =
    ProcessLoop.restarting("analytics", config.interval, systemAlerts)(runBatch)

  private def runBatch: F[Unit] =
    for {
      accountList <- accounts.allAccounts
      today <- UtcClock.todayUtc[F]
      _ <- processAccounts(accountList.map(_.id), today).compile.drain
    } yield ()

  private def processAccounts(ids: List[AccountId], today: LocalDate): Stream[F, Unit] =
    Stream.emits(ids).flatMap(processAccount(_, today))

  private def processAccount(accountId: AccountId, today: LocalDate): Stream[F, Unit] =
    Stream
      .eval(TraceIds.next[F])
      .flatMap { uuid =>
        Stream.eval(
          recompute(accountId, today).local(_.copy(traceId = uuid, accountId = accountId))
        )
      }
      .handleErrorWith { e =>
        Stream.eval(errorCause"Analytics recompute failed for account ${accountId.value}" (e))
      }

  private def recompute(accountId: AccountId, today: LocalDate): F[Unit] =
    for {
      series <- valuationService.dailySeries(
        accountId,
        today.minusDays(config.recomputeDays.toLong),
        today
      )
      _ <- valuations.upsert(accountId, series.currency, series.points)
      _ <- checkDrift(accountId, today)
    } yield ()

  private def checkDrift(accountId: AccountId, today: LocalDate): F[Unit] =
    for {
      accountTargets <- targets.byAccount(accountId)
      snapshot <- valuationService.positionsAsOf(accountId, today)
      drifts = Rebalancer.drift(Allocation.of(snapshot), snapshot, accountTargets)
      now <- UtcClock.nowUtc[F]
      _ <- drifts.filter(_.breached).traverse(dispatchDrift(accountId, _, now))
    } yield ()

  private def dispatchDrift(accountId: AccountId, d: Drift, now: ZonedDateTime): F[Unit] = {
    val ruleKey = s"drift:${accountId.value}:${d.dimension.value}:${d.key}"
    val alert = Alert(
      ruleKey,
      None,
      AlertKind.AllocationDrift,
      s"Allocation drift: ${d.dimension.value}/${d.key}",
      s"Target ${pct(d.targetPct)}, actual ${pct(d.actualPct)}, drift ${pct(d.driftPct)}",
      Some(d.driftPct),
      now
    )
    dispatcher.dispatch(alert, AnalyticsProcessImpl.DriftAlertPolicy, channels).void
  }

  private def pct(v: BigDecimal): String =
    s"${v.setScale(AnalyticsProcessImpl.PercentScale, RoundingMode.HALF_UP)}%"
}

object AnalyticsProcessImpl {

  // One AllocationDrift alert per breached target per day at most - drift is re-evaluated every
  // tick, but it rarely needs more than a daily reminder.
  private val DriftAlertPolicy = DeliveryPolicy(cooldown = 1.day)

  private val PercentScale = 2

  /** Wires the analytics process backed by the given storages, valuation service and dispatcher.
    */
  def create[I[_]: Functor, F[_]: Temporal: GenUUID](
    accounts: AccountStorage[F],
    valuationService: PortfolioValuationService[F],
    valuations: PortfolioValuationStorage[F],
    targets: AllocationTargetStorage[F],
    dispatcher: AlertDispatcher[F],
    systemAlerts: SystemAlerts[F],
    config: AnalyticsConfig,
    channels: List[String]
  )(implicit logs: Logs[I, F], WL: WithLocal[F, ProcessContext]): I[AnalyticsProcessImpl[F]] =
    logs
      .forService[AnalyticsProcess[F]]
      .map(implicit log =>
        new AnalyticsProcessImpl[F](
          accounts,
          valuationService,
          valuations,
          targets,
          dispatcher,
          systemAlerts,
          config,
          channels
        )
      )
}
