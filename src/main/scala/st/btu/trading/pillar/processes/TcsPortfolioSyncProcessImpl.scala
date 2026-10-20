package st.btu.trading.pillar.processes

import cats.Functor
import cats.effect.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import fs2.Stream
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.data.{ProcessContext, TraceIds}
import st.btu.trading.pillar.facades.TcsFacadeImpl
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.tcs
import st.btu.trading.pillar.services.{TcsPortfolioService, TcsPortfolioServiceImpl}
import st.btu.trading.pillar.storages.{AccountStorage, TcsPortfolioOperationStorage}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}
import tofu.syntax.context.*

import java.time.ZonedDateTime
import scala.concurrent.duration.*

final class TcsPortfolioSyncProcessImpl[F[_]: Temporal: Logging: GenUUID](
  accountStorage: AccountStorage[F],
  tcsPortfolioService: TcsPortfolioService[F],
  systemAlerts: SystemAlerts[F]
)(implicit WL: WithLocal[F, ProcessContext])
    extends TcsPortfolioSyncProcess[F] {

  def run: Stream[F, Unit] =
    Stream
      .evals(accountStorage.allAccounts)
      .map(_.id)
      .map { accountId =>
        Stream
          .eval(Ref[F].of(Option.empty[ZonedDateTime]))
          .flatMap(processAccountPortfolioSync(accountId, _))
      }
      .parJoinUnbounded

  private def processAccountPortfolioSync(
    accountId: AccountId,
    lastDtRef: Ref[F, Option[ZonedDateTime]]
  ): Stream[F, Unit] = {
    val syncF = lastDtRef.get.flatMap(
      tcsPortfolioService.syncOperations(1.days, 1.hours, _).flatMap(lastDtRef.set)
    )
    val tracedSync =
      TraceIds.next[F].flatMap(uuid => syncF.local(_.copy(traceId = uuid, accountId = accountId)))

    ProcessLoop.restarting(
      "tcs-portfolio-sync",
      TcsPortfolioSyncProcessImpl.SyncInterval,
      systemAlerts
    )(tracedSync)
  }
}

object TcsPortfolioSyncProcessImpl {

  /** How long the process waits after syncing an account's operations before it syncs them again,
    * and after a crash before it restarts.
    */
  val SyncInterval: FiniteDuration = 2.minutes

  /** Same as the overload taking `systemAlerts`, with no system alerts raised. */
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    initialYearsAgo: Int,
    accountStorage: AccountStorage[F],
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F]
  )(implicit
    logs: Logs[I, F],
    liftFI: Lift[F, I],
    WL: WithLocal[F, ProcessContext]
  ): Resource[I, TcsPortfolioSyncProcessImpl[F]] =
    resource[I, F](
      brokerAccountId,
      tcsToken,
      initialYearsAgo,
      accountStorage,
      tcsPortfolioOperationStorage,
      SystemAlerts.noop[F]
    )

  /** Wires the Tinkoff portfolio sync process, authenticated as `brokerAccountId`, holding its gRPC
    * channel open for the resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    initialYearsAgo: Int,
    accountStorage: AccountStorage[F],
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F],
    systemAlerts: SystemAlerts[F]
  )(implicit
    logs: Logs[I, F],
    liftFI: Lift[F, I],
    WL: WithLocal[F, ProcessContext]
  ): Resource[I, TcsPortfolioSyncProcessImpl[F]] =
    for {
      tcsFacade <- TcsFacadeImpl.resource[I, F](brokerAccountId, tcsToken)
      tcsPortfolioService <- Resource.eval(
        TcsPortfolioServiceImpl
          .create[I, F](initialYearsAgo, tcsPortfolioOperationStorage, tcsFacade)
      )
      tcsPortfolioSyncProcess <- Resource.eval(
        TcsPortfolioSyncProcessImpl.create[I, F](accountStorage, tcsPortfolioService, systemAlerts)
      )
    } yield tcsPortfolioSyncProcess

  /** Same as the overload taking `systemAlerts`, with no system alerts raised. */
  def create[I[_]: Functor, F[_]: Temporal: GenUUID](
    accountStorage: AccountStorage[F],
    tcsPortfolioService: TcsPortfolioService[F]
  )(implicit
    logs: Logs[I, F],
    WL: WithLocal[F, ProcessContext]
  ): I[TcsPortfolioSyncProcessImpl[F]] =
    create[I, F](accountStorage, tcsPortfolioService, SystemAlerts.noop[F])

  /** Wires the Tinkoff portfolio sync process backed by `accountStorage` and `tcsPortfolioService`.
    */
  def create[I[_]: Functor, F[_]: Temporal: GenUUID](
    accountStorage: AccountStorage[F],
    tcsPortfolioService: TcsPortfolioService[F],
    systemAlerts: SystemAlerts[F]
  )(implicit
    logs: Logs[I, F],
    WL: WithLocal[F, ProcessContext]
  ): I[TcsPortfolioSyncProcessImpl[F]] =
    logs
      .forService[TcsPortfolioSyncProcess[F]]
      .map(implicit log =>
        new TcsPortfolioSyncProcessImpl[F](accountStorage, tcsPortfolioService, systemAlerts)
      )
}
