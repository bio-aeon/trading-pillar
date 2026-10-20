package st.btu.trading.pillar.processes

import cats.effect.{IO, Ref}
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.{FailureTracker, FailureTrackerImpl, SystemAlerts}
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{MarketDataError, Source, Ticker, WatchlistItem}
import st.btu.trading.pillar.models.market.Ticker.value
import st.btu.trading.pillar.services.{PriceHistorySyncConfig, PriceHistorySyncService, SyncReport}
import st.btu.trading.pillar.storages.WatchlistStorage
import st.btu.trading.pillar.testkit.{AlertFakes, MarketDataFakes}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}
import tofu.WithLocal

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class PriceHistorySyncProcessSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)

  private def item(symbol: String): WatchlistItem =
    WatchlistItem(
      accountId,
      Ticker(symbol),
      enabled = true,
      None,
      ZonedDateTime.now(ZoneOffset.UTC)
    )

  // Small real sleeps so the spec runs fast while still exercising the interval/backoff logic.
  private val fastConfig = PriceHistorySyncConfig(
    interval = 10.millis,
    backfillYears = 2,
    overlapDays = 5,
    rateLimitBackoff = 10.millis,
    metadataRefresh = 24.hours
  )

  private def freshFailureTracker: IO[FailureTracker[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[FailureTracker[IO]])
    FailureTrackerImpl.create[IO, IO]
  }

  private def buildProcess(
    watchlist: WatchlistStorage[IO],
    service: PriceHistorySyncService[IO],
    config: PriceHistorySyncConfig,
    systemAlerts: SystemAlerts[IO] = SystemAlerts.noop[IO],
    failureTracker: IO[FailureTracker[IO]] = freshFailureTracker
  ): IO[PriceHistorySyncProcessImpl[IO]] =
    MarketDataFakes.withLocal(ProcessContext("init", accountId)).flatMap { wl =>
      implicit val withLocal: WithLocal[IO, ProcessContext] = wl
      implicit val genUUID: GenUUID[IO] = GenUUID.syncGenUUID[IO]
      implicit val logs: Logs[IO, IO] =
        Logs.const[IO, IO](Logging.Make.plain[IO].forService[PriceHistorySyncProcess[IO]])
      failureTracker.flatMap(tracker =>
        PriceHistorySyncProcessImpl
          .create[IO, IO](watchlist, service, config, systemAlerts, tracker)
      )
    }

  "PriceHistorySyncProcessImpl" >> {
    "run" >> {

      "continues with the next item when one raises a non-rate-limit error" >> {
        val badTicker = Ticker("BAD")
        val okTicker = Ticker("AAPL")

        for {
          service <- MarketDataFakes.priceHistorySyncService(
            Map(badTicker -> IO.raiseError(MarketDataError.NotFound(badTicker)))
          )
          watchlist <- MarketDataFakes.watchlistStorage(List(item("BAD"), item("AAPL")))
          process <- buildProcess(watchlist, service, fastConfig)
          _ <- process.run.take(1).compile.drain
          synced <- service.syncedTickers
        } yield synced must contain(badTicker, okTicker)
      }

      "backs off on a rate-limit error and still syncs the next item" >> {
        val rateLimitedTicker = Ticker("RL")
        val okTicker = Ticker("AAPL")

        for {
          service <- MarketDataFakes.priceHistorySyncService(
            Map(rateLimitedTicker -> IO.raiseError(MarketDataError.RateLimited(Some(5.millis))))
          )
          watchlist <- MarketDataFakes.watchlistStorage(List(item("RL"), item("AAPL")))
          process <- buildProcess(watchlist, service, fastConfig)
          _ <- process.run.take(1).compile.drain
          synced <- service.syncedTickers
        } yield synced must contain(rateLimitedTicker, okTicker)
      }

      "raises a sync-failed system alert once, at the third consecutive failure, then sync-recovered on the next success" >> {
        val ticker = Ticker("FLAKY")
        val report = SyncReport(ticker, Source.Yahoo, 1, 1, 0, LocalDate.now, LocalDate.now)

        for {
          countRef <- Ref.of[IO, Int](0)
          flaky = countRef.getAndUpdate(_ + 1).flatMap { n =>
            if (n < 3) IO.raiseError(new RuntimeException(s"boom-$n")) else IO.pure(report)
          }
          service <- MarketDataFakes.priceHistorySyncService(Map(ticker -> flaky))
          watchlist <- MarketDataFakes.watchlistStorage(List(item("FLAKY")))
          alerts <- AlertFakes.systemAlerts()
          process <- buildProcess(watchlist, service, fastConfig, alerts)
          _ <- process.run.take(4).compile.drain
          raised <- alerts.raised
        } yield raised.map(_.name) must_== List(
          s"sync-failed:${ticker.value}",
          s"sync-recovered:${ticker.value}"
        )
      }

      "raises no system alert when failures stay below the threshold before succeeding" >> {
        val ticker = Ticker("OK")
        val report = SyncReport(ticker, Source.Yahoo, 1, 1, 0, LocalDate.now, LocalDate.now)

        for {
          countRef <- Ref.of[IO, Int](0)
          flaky = countRef.getAndUpdate(_ + 1).flatMap { n =>
            if (n < 2) IO.raiseError(new RuntimeException(s"boom-$n")) else IO.pure(report)
          }
          service <- MarketDataFakes.priceHistorySyncService(Map(ticker -> flaky))
          watchlist <- MarketDataFakes.watchlistStorage(List(item("OK")))
          alerts <- AlertFakes.systemAlerts()
          process <- buildProcess(watchlist, service, fastConfig, alerts)
          _ <- process.run.take(3).compile.drain
          raised <- alerts.raised
        } yield raised must beEmpty
      }
    }
  }
}
