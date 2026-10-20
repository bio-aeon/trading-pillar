package st.btu.trading.pillar.processes

import cats.Applicative
import cats.effect.{Async, Ref, Sync, Temporal}
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import fs2.Stream
import st.btu.trading.pillar.alerts.{FailureTracker, SystemAlerts}
import st.btu.trading.pillar.data.{ProcessContext, TraceIds, UtcClock}
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker, WatchlistItem}
import st.btu.trading.pillar.services.{PriceHistorySyncConfig, PriceHistorySyncService}
import st.btu.trading.pillar.storages.WatchlistStorage
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}
import tofu.syntax.context.*
import tofu.syntax.logging.*

import java.time.LocalDate

// Unlike the Tcs process, watchlist items are synced sequentially (not in parallel) so that a
// single rate-limited item can back off without racing the others against the same upstream limit.
final class PriceHistorySyncProcessImpl[F[_]: Temporal: Logging: GenUUID](
  watchlist: WatchlistStorage[F],
  syncService: PriceHistorySyncService[F],
  config: PriceHistorySyncConfig,
  systemAlerts: SystemAlerts[F],
  failureTracker: FailureTracker[F],
  // Tickers for which sync-failed has been raised and not yet followed by sync-recovered.
  alertingRef: Ref[F, Set[Ticker]]
)(implicit WL: WithLocal[F, ProcessContext])
    extends PriceHistorySyncProcess[F] {

  import PriceHistorySyncProcessImpl.FailureThreshold

  def run: Stream[F, Unit] =
    ProcessLoop.restarting("price-history-sync", config.interval, systemAlerts)(runBatch)

  private def runBatch: F[Unit] =
    for {
      items <- watchlist.allEnabled
      today <- UtcClock.todayUtc[F]
      _ <- processItems(items, today).compile.drain
    } yield ()

  private def processItems(items: List[WatchlistItem], today: LocalDate): Stream[F, Unit] =
    Stream.emits(items).flatMap(processItem(_, today))

  private def processItem(item: WatchlistItem, today: LocalDate): Stream[F, Unit] =
    Stream
      .eval(TraceIds.next[F])
      .flatMap { uuid =>
        Stream.eval(syncOne(item, today).local(_.copy(traceId = uuid, accountId = item.accountId)))
      }
      .handleErrorWith {
        case MarketDataError.RateLimited(retryAfter) =>
          Stream.eval(warn"Rate limited syncing ${item.ticker.value}; backing off") ++
            Stream.sleep[F](retryAfter.getOrElse(config.rateLimitBackoff))
        case e =>
          Stream.eval(errorCause"Price history sync failed for ${item.ticker.value}" (e))
      }

  // The tracker/alert bookkeeping runs inside this handleErrorWith (re-raising afterwards) so the
  // Stream-level handleErrorWith above still sees the original error for its own logging/backoff.
  private def syncOne(item: WatchlistItem, today: LocalDate): F[Unit] =
    (syncService.syncSymbol(item, today).void >> onSuccess(item.ticker))
      .handleErrorWith(e => onFailure(item.ticker) >> e.raiseError[F, Unit])

  private def onFailure(ticker: Ticker): F[Unit] =
    failureTracker.failed(ticker.value).flatMap { count =>
      Applicative[F].whenA(count == FailureThreshold)(
        alertingRef.update(_ + ticker) >>
          systemAlerts.raise(
            s"sync-failed:${ticker.value}",
            s"Price sync failing: ${ticker.value}",
            s"$FailureThreshold consecutive price-history sync failures for ${ticker.value}"
          )
      )
    }

  private def onSuccess(ticker: Ticker): F[Unit] =
    failureTracker.succeeded(ticker.value) >>
      alertingRef
        .modify(current => (current - ticker, current.contains(ticker)))
        .flatMap { wasAlerting =>
          Applicative[F].whenA(wasAlerting)(
            systemAlerts.raise(
              s"sync-recovered:${ticker.value}",
              s"Price sync recovered: ${ticker.value}",
              s"Sync for ${ticker.value} succeeded after $FailureThreshold+ consecutive failures"
            )
          )
        }

}

object PriceHistorySyncProcessImpl {

  val FailureThreshold = 3

  // F needs Async (not just Temporal) here, purely to allocate the alertingRef Ref during
  // construction; the class itself (and everything it does per tick) only ever needs Temporal.
  /** Wires the price-history sync process backed by the given watchlist and sync service. */
  def create[I[_]: Sync, F[_]: Async: GenUUID](
    watchlist: WatchlistStorage[F],
    syncService: PriceHistorySyncService[F],
    config: PriceHistorySyncConfig,
    systemAlerts: SystemAlerts[F],
    failureTracker: FailureTracker[F]
  )(implicit
    logs: Logs[I, F],
    WL: WithLocal[F, ProcessContext]
  ): I[PriceHistorySyncProcessImpl[F]] =
    for {
      alertingRef <- Ref.in[I, F, Set[Ticker]](Set.empty)
      process <- logs
        .forService[PriceHistorySyncProcess[F]]
        .map(implicit log =>
          new PriceHistorySyncProcessImpl[F](
            watchlist,
            syncService,
            config,
            systemAlerts,
            failureTracker,
            alertingRef
          )
        )
    } yield process
}
