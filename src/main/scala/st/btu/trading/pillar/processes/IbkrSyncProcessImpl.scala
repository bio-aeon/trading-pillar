package st.btu.trading.pillar.processes

import cats.{Functor, MonadThrow}
import cats.effect.*
import cats.syntax.flatMap.*
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
import st.btu.trading.pillar.services.{IbkrSyncConfig, IbkrSyncService, IbkrSyncServiceImpl}
import st.btu.trading.pillar.storages.{BrokerInstrumentStorage, IbkrStatementStorage}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}

import scala.concurrent.duration.FiniteDuration

/** Syncs the statements of every IB account it is given, each in a loop of its own, so that one
  * account's failure never stops the others. An account that is not given is not synced.
  *
  * A loop first records every statement row already stored for its account again, with its
  * translation, and then syncs the account every `interval`. Both run in a `ProcessContext` that
  * names the account, and a crash raises a system alert named after the account. A crashed loop
  * starts again after `interval` too.
  */
final class IbkrSyncProcessImpl[F[_]: Temporal: Logging: GenUUID](
  accounts: List[IbkrSyncProcessImpl.AccountSync[F]],
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
      account.service.sync
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
    * instruments they describe are recorded in the given storages.
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
      process <- create[I, F](syncs, systemAlerts, interval)
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

  /** Wires the IB sync process over `accounts`, syncing each every `interval`. */
  def create[I[_]: Functor, F[_]: Temporal: GenUUID](
    accounts: List[AccountSync[F]],
    systemAlerts: SystemAlerts[F],
    interval: FiniteDuration
  )(implicit logs: Logs[I, F], WL: WithLocal[F, ProcessContext]): I[IbkrSyncProcessImpl[F]] =
    logs
      .forService[IbkrSyncProcess[F]]
      .map(implicit log => new IbkrSyncProcessImpl[F](accounts, systemAlerts, interval))
}
