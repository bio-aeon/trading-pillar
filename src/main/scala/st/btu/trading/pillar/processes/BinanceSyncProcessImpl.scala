package st.btu.trading.pillar.processes

import cats.Functor
import cats.effect.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import fs2.Stream
import sttp.client3.SttpBackend
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.gateways.binance.{BinanceApiConfig, BinanceApiImpl, BinanceIpBuckets}
import st.btu.trading.pillar.models.binance.BinanceAccount
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.security.Crypto
import st.btu.trading.pillar.services.{
  BinanceSyncConfig,
  BinanceSyncService,
  BinanceSyncServiceImpl
}
import st.btu.trading.pillar.storages.{
  BinanceMinutePriceStorage,
  BinancePairStorage,
  BinanceRecordStorage,
  BrokerInstrumentStorage
}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}

import scala.concurrent.duration.FiniteDuration

/** Syncs the records of every Binance account it is given, each in a loop of its own, so that one
  * account's failure never stops the others. An account that is not given is not synced.
  *
  * A loop first records every record already stored for its account again, with its translation,
  * and then syncs the account every `interval`. All of it runs in a `ProcessContext` that names the
  * account, and a crash raises a system alert named after the account. A crashed loop starts again
  * after `interval` too, with the service it had, which keeps in memory where each source was last
  * read.
  */
final class BinanceSyncProcessImpl[F[_]: Temporal: Logging: GenUUID](
  accounts: List[BinanceSyncProcessImpl.AccountSync[F]],
  systemAlerts: SystemAlerts[F],
  interval: FiniteDuration
)(implicit WL: WithLocal[F, ProcessContext])
    extends BinanceSyncProcess[F] {
  import BinanceSyncProcessImpl.*

  def run: Stream[F, Unit] =
    Stream.emits(accounts).map(processAccountSync).parJoinUnbounded

  private def processAccountSync(account: AccountSync[F]): Stream[F, Unit] =
    AccountSyncLoop.restarting(ProcessName, account.accountId, interval, systemAlerts)(
      account.service.rebuildTranslations,
      account.service.sync
    )
}

object BinanceSyncProcessImpl {

  private val ProcessName = "binance-sync"

  /** The sync of one account: the account its records are stored under, and the service that reads
    * them from the Binance account behind it.
    */
  final case class AccountSync[F[_]](accountId: AccountId, service: BinanceSyncService[F])

  /** Wires the Binance sync process for `accounts`, each reading with its own key. The limits that
    * Binance counts per IP address are shared by every account, so all the accounts' calls are
    * paced together. The records, the pairs, the prices and the instruments are recorded in the
    * given storages, and the syncs raise their alerts through `systemAlerts`.
    *
    * @param crypto
    *   signs the requests
    * @param interval
    *   how long the process waits after syncing an account before it syncs it again, and after a
    *   crash before it restarts
    */
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    accounts: List[BinanceAccount],
    config: BinanceSyncConfig,
    interval: FiniteDuration,
    recordStorage: BinanceRecordStorage[F],
    pairStorage: BinancePairStorage[F],
    priceStorage: BinanceMinutePriceStorage[F],
    instrumentStorage: BrokerInstrumentStorage[F],
    systemAlerts: SystemAlerts[F],
    crypto: Crypto[F],
    backend: SttpBackend[F, Any],
    apiConfig: BinanceApiConfig = BinanceApiConfig()
  )(implicit
    logs: Logs[I, F],
    WL: WithLocal[F, ProcessContext]
  ): Resource[I, BinanceSyncProcessImpl[F]] =
    Resource.eval(for {
      ipBuckets <- BinanceIpBuckets.create[I, F]
      syncs <- accounts.traverse { account =>
        for {
          api <- BinanceApiImpl
            .create[I, F](account.apiKey, account.secretKey, apiConfig, crypto, backend, ipBuckets)
          service <- BinanceSyncServiceImpl.create[I, F](
            account,
            config,
            api,
            recordStorage,
            pairStorage,
            priceStorage,
            instrumentStorage,
            systemAlerts
          )
        } yield AccountSync(account.accountId, service)
      }
      process <- create[I, F](syncs, systemAlerts, interval)
    } yield process)

  /** Wires the Binance sync process over `accounts`, syncing each every `interval`. */
  def create[I[_]: Functor, F[_]: Temporal: GenUUID](
    accounts: List[AccountSync[F]],
    systemAlerts: SystemAlerts[F],
    interval: FiniteDuration
  )(implicit logs: Logs[I, F], WL: WithLocal[F, ProcessContext]): I[BinanceSyncProcessImpl[F]] =
    logs
      .forService[BinanceSyncProcess[F]]
      .map(implicit log => new BinanceSyncProcessImpl[F](accounts, systemAlerts, interval))
}
