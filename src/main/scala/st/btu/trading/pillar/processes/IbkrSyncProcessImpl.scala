package st.btu.trading.pillar.processes

import cats.{Functor, MonadThrow}
import cats.effect.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import fs2.Stream
import sttp.client3.SttpBackend
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.gateways.ibkr.{
  FlexWebService,
  FlexWebServiceConfig,
  FlexWebServiceImpl
}
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.ibkr.{FlexToken, IbkrAccount}
import st.btu.trading.pillar.services.{
  IbkrReconciler,
  IbkrSyncConfig,
  IbkrSyncService,
  IbkrSyncServiceImpl
}
import st.btu.trading.pillar.storages.{
  AccountOperationStorage,
  BrokerInstrumentStorage,
  IbkrStatementStorage
}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}

import scala.concurrent.duration.FiniteDuration

/** Syncs the statements of every IB account it is given, each in a loop of its own, so that one
  * account's failure never stops the others. An account that is not given is not synced.
  *
  * A loop first records every statement row already stored for its account again, with its
  * translation, and then syncs the account every `interval`. After a sync that read a window it
  * reconciles the account's recorded positions with the Open Positions of that window. All of it
  * runs in a `ProcessContext` that names the account, and a crash raises a system alert named after
  * the account. A crashed loop starts again after `interval` too.
  */
final class IbkrSyncProcessImpl[F[_]: Temporal: Logging: GenUUID](
  accounts: List[IbkrSyncProcessImpl.AccountSync[F]],
  reconciler: IbkrReconciler[F],
  systemAlerts: SystemAlerts[F],
  interval: FiniteDuration
)(implicit WL: WithLocal[F, ProcessContext])
    extends IbkrSyncProcess[F] {
  import IbkrSyncProcessImpl.*

  def run: Stream[F, Unit] =
    Stream.emits(accounts).map(processAccountSync).parJoinUnbounded

  private def processAccountSync(account: AccountSync[F]): Stream[F, Unit] =
    AccountSyncLoop.restarting(ProcessName, account.accountId, interval, systemAlerts)(
      account.service.rebuildTranslations,
      account.service.sync.flatMap(_.traverse_(reconciler.reconcile(account.accountId, _)))
    )
}

object IbkrSyncProcessImpl {

  private val ProcessName = "ibkr-sync"

  /** The sync of one account: the account its statement rows are stored under, and the service that
    * reads them from the IB account behind it.
    */
  final case class AccountSync[F[_]](accountId: AccountId, service: IbkrSyncService[F])

  /** Wires the IB sync process for `accounts`, all reading with `token` through one
    * `FlexWebService`, so that their calls share the token's rate limit. The statement rows and the
    * instruments they describe are recorded in the given storages, and `operations` is where the
    * reconciliation reads the positions recorded for an account.
    *
    * @param interval
    *   how long the process waits after syncing an account before it syncs it again, and after a
    *   crash before it restarts
    */
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    accounts: List[IbkrAccount],
    token: FlexToken,
    config: IbkrSyncConfig,
    interval: FiniteDuration,
    statementStorage: IbkrStatementStorage[F],
    operations: AccountOperationStorage[F],
    instrumentStorage: BrokerInstrumentStorage[F],
    systemAlerts: SystemAlerts[F],
    backend: SttpBackend[F, Any],
    flexConfig: FlexWebServiceConfig = FlexWebServiceConfig()
  )(implicit
    logs: Logs[I, F],
    WL: WithLocal[F, ProcessContext]
  ): Resource[I, IbkrSyncProcessImpl[F]] =
    Resource.eval(for {
      flex <- FlexWebServiceImpl.create[I, F](token, flexConfig, backend)
      syncs <- accounts.traverse(
        accountSync[I, F](_, config, flex, statementStorage, instrumentStorage)
      )
      process <- create[I, F](syncs, operations, instrumentStorage, systemAlerts, interval)
    } yield process)

  private def accountSync[I[_]: Functor, F[_]: MonadThrow: Clock](
    account: IbkrAccount,
    config: IbkrSyncConfig,
    flex: FlexWebService[F],
    statementStorage: IbkrStatementStorage[F],
    instrumentStorage: BrokerInstrumentStorage[F]
  )(implicit logs: Logs[I, F]): I[AccountSync[F]] =
    IbkrSyncServiceImpl
      .create[I, F](account, config, flex, statementStorage, instrumentStorage)
      .map(AccountSync(account.accountId, _))

  /** Wires the IB sync process over `accounts`, syncing each every `interval`, and reconciling the
    * positions each has recorded, which it reads from `operations`, with the Open Positions its
    * sync reports. An instrument IB does not report is named by its record in `instruments`.
    */
  def create[I[_]: Sync, F[_]: Async: GenUUID](
    accounts: List[AccountSync[F]],
    operations: AccountOperationStorage[F],
    instruments: BrokerInstrumentStorage[F],
    systemAlerts: SystemAlerts[F],
    interval: FiniteDuration
  )(implicit logs: Logs[I, F], WL: WithLocal[F, ProcessContext]): I[IbkrSyncProcessImpl[F]] =
    for {
      reconciler <- IbkrReconciler.create[I, F](operations, instruments, systemAlerts)
      process <- logs
        .forService[IbkrSyncProcess[F]]
        .map(implicit log =>
          new IbkrSyncProcessImpl[F](accounts, reconciler, systemAlerts, interval)
        )
    } yield process
}
