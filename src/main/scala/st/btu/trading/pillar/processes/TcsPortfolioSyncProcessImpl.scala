package st.btu.trading.pillar.processes

import cats.Functor
import cats.effect.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import fs2.Stream
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.data.{ProcessContext, TraceIds}
import st.btu.trading.pillar.facades.TcsFacadeImpl
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.tcs
import st.btu.trading.pillar.services.{
  TcsPortfolioService,
  TcsPortfolioServiceImpl,
  TcsPortfolioSyncConfig
}
import st.btu.trading.pillar.storages.TcsPortfolioOperationStorage
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}
import tofu.syntax.context.*

import java.time.ZonedDateTime
import scala.concurrent.duration.*

/** Syncs the operations of every account it is given, each in a loop of its own, so that one
  * account's failure never stops the others. An account that is not given is not synced.
  *
  * A loop first records every operation already stored for its account again, with its translation,
  * and then syncs the account every `interval`. Both run in a `ProcessContext` that names the
  * account, and a crash raises a system alert named after the account.
  */
final class TcsPortfolioSyncProcessImpl[F[_]: Temporal: Logging: GenUUID](
  accounts: List[TcsPortfolioSyncProcessImpl.AccountSync[F]],
  systemAlerts: SystemAlerts[F],
  interval: FiniteDuration
)(implicit WL: WithLocal[F, ProcessContext])
    extends TcsPortfolioSyncProcess[F] {
  import TcsPortfolioSyncProcessImpl.*

  def run: Stream[F, Unit] =
    Stream.emits(accounts).map(processAccountPortfolioSync).parJoinUnbounded

  private def processAccountPortfolioSync(account: AccountSync[F]): Stream[F, Unit] =
    Stream
      .eval((Ref[F].of(false), Ref[F].of(Option.empty[ZonedDateTime])).tupled)
      .flatMap { case (translationsRebuilt, lastDtRef) =>
        val rebuildF = translationsRebuilt.get.ifM(
          Temporal[F].unit,
          account.service.rebuildTranslations >> translationsRebuilt.set(true)
        )
        val syncF = lastDtRef.get.flatMap(
          account.service.syncOperations(WindowLength, WindowOverlap, _).flatMap { reached =>
            lastDtRef.set(Some(reached))
          }
        )
        val tracedSync = TraceIds
          .next[F]
          .flatMap(uuid =>
            (rebuildF >> syncF).local(_.copy(traceId = uuid, accountId = account.accountId))
          )

        ProcessLoop.restarting(s"$ProcessName:${account.accountId.value}", interval, systemAlerts)(
          tracedSync
        )
      }
}

object TcsPortfolioSyncProcessImpl {

  /** How long the process waits after syncing an account's operations before it syncs them again,
    * and after a crash before it restarts.
    */
  val SyncInterval: FiniteDuration = 2.minutes

  // The span of operations one sync asks for, and how far each window reaches back into the one
  // before it.
  private val WindowLength = 1.day
  private val WindowOverlap = 1.hour

  private val ProcessName = "tcs-portfolio-sync"

  /** The sync of one account: the account its operations are stored under, and the service that
    * fetches them from the Tinkoff account behind it.
    */
  final case class AccountSync[F[_]](accountId: AccountId, service: TcsPortfolioService[F])

  /** Same as the overload taking `systemAlerts`, with no system alerts raised. */
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    accounts: List[tcs.TcsAccount],
    tcsToken: tcs.Token,
    config: TcsPortfolioSyncConfig,
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F]
  )(implicit
    logs: Logs[I, F],
    liftFI: Lift[F, I],
    WL: WithLocal[F, ProcessContext]
  ): Resource[I, TcsPortfolioSyncProcessImpl[F]] =
    resource[I, F](accounts, tcsToken, config, tcsPortfolioOperationStorage, SystemAlerts.noop[F])

  /** Wires the Tinkoff portfolio sync process for `accounts`, each reading its own Tinkoff account
    * with `tcsToken` over a gRPC channel of its own, which stays open for the resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    accounts: List[tcs.TcsAccount],
    tcsToken: tcs.Token,
    config: TcsPortfolioSyncConfig,
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F],
    systemAlerts: SystemAlerts[F]
  )(implicit
    logs: Logs[I, F],
    liftFI: Lift[F, I],
    WL: WithLocal[F, ProcessContext]
  ): Resource[I, TcsPortfolioSyncProcessImpl[F]] =
    for {
      syncs <- accounts.traverse(
        accountSync[I, F](_, tcsToken, config, tcsPortfolioOperationStorage)
      )
      tcsPortfolioSyncProcess <- Resource.eval(create[I, F](syncs, systemAlerts))
    } yield tcsPortfolioSyncProcess

  private def accountSync[I[_]: Sync, F[_]: Async: GenUUID](
    account: tcs.TcsAccount,
    tcsToken: tcs.Token,
    config: TcsPortfolioSyncConfig,
    tcsPortfolioOperationStorage: TcsPortfolioOperationStorage[F]
  )(implicit
    logs: Logs[I, F],
    liftFI: Lift[F, I],
    WL: WithLocal[F, ProcessContext]
  ): Resource[I, AccountSync[F]] =
    for {
      tcsFacade <- TcsFacadeImpl.resource[I, F](account.brokerAccountId, tcsToken)
      tcsPortfolioService <- Resource.eval(
        TcsPortfolioServiceImpl.create[I, F](config, tcsPortfolioOperationStorage, tcsFacade)
      )
    } yield AccountSync(account.accountId, tcsPortfolioService)

  /** Same as the overload taking `systemAlerts`, with no system alerts raised. */
  def create[I[_]: Functor, F[_]: Temporal: GenUUID](accounts: List[AccountSync[F]])(implicit
    logs: Logs[I, F],
    WL: WithLocal[F, ProcessContext]
  ): I[TcsPortfolioSyncProcessImpl[F]] =
    create[I, F](accounts, SystemAlerts.noop[F])

  /** Wires the Tinkoff portfolio sync process over `accounts`, syncing each every [[SyncInterval]].
    */
  def create[I[_]: Functor, F[_]: Temporal: GenUUID](
    accounts: List[AccountSync[F]],
    systemAlerts: SystemAlerts[F]
  )(implicit
    logs: Logs[I, F],
    WL: WithLocal[F, ProcessContext]
  ): I[TcsPortfolioSyncProcessImpl[F]] =
    logs
      .forService[TcsPortfolioSyncProcess[F]]
      .map(implicit log => new TcsPortfolioSyncProcessImpl[F](accounts, systemAlerts, SyncInterval))
}
