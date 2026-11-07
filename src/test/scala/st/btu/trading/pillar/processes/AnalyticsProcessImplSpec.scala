package st.btu.trading.pillar.processes

import cats.effect.{IO, Ref}
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.{AlertDispatcher, AlertKind, SystemAlerts}
import st.btu.trading.pillar.analytics.portfolio.*
import st.btu.trading.pillar.data.{ProcessContext, UtcClock}
import st.btu.trading.pillar.models.Account
import st.btu.trading.pillar.models.domain.{AccountId, Broker, Currency, Figi}
import st.btu.trading.pillar.storages.{
  AccountStorage,
  AllocationTargetStorage,
  PortfolioSnapshotStorage,
  PortfolioValuationStorage
}
import st.btu.trading.pillar.testkit.{AlertFakes, AnalyticsFakes, MarketDataFakes}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZonedDateTime}
import scala.concurrent.duration.*

class AnalyticsProcessImplSpec extends Specification with CatsEffect {

  private val accountId1 = AccountId(1)
  private val accountId2 = AccountId(2)
  private val rub = Currency("RUB")
  private val fastConfig = AnalyticsConfig(interval = 10.millis, recomputeDays = 30)

  private def account(id: AccountId, name: String): Account =
    Account(id, name, Broker.Tinkoff, ZonedDateTime.now)

  private def position(
    instrumentType: String,
    weight: BigDecimal,
    currency: Option[String] = None
  ): PositionValue =
    PositionValue(
      Figi("F1"),
      ticker = None,
      name = None,
      instrumentType = Some(instrumentType),
      currency = currency.map(Currency(_)),
      exchange = None,
      source = None,
      quantity = 1,
      price = None,
      value = None,
      weight = Some(weight),
      dayReturn = None
    )

  // A valuation point with no cash flow on its day.
  private def point(date: LocalDate, value: Int): ValuationPoint =
    ValuationPoint(date, value, invested = 1000, cashFlow = 0)

  private def buildProcess(
    accountStorage: AccountStorage[IO],
    valuationService: PortfolioValuationService[IO],
    valuations: PortfolioValuationStorage[IO],
    snapshots: PortfolioSnapshotStorage[IO],
    targets: AllocationTargetStorage[IO],
    dispatcher: AlertDispatcher[IO],
    systemAlerts: SystemAlerts[IO] = SystemAlerts.noop[IO]
  ): IO[AnalyticsProcessImpl[IO]] =
    MarketDataFakes.withLocal(ProcessContext("init", accountId1)).flatMap { wl =>
      implicit val withLocal: WithLocal[IO, ProcessContext] = wl
      implicit val genUUID: GenUUID[IO] = GenUUID.syncGenUUID[IO]
      implicit val logs: Logs[IO, IO] =
        Logs.const[IO, IO](Logging.Make.plain[IO].forService[AnalyticsProcess[IO]])
      AnalyticsProcessImpl.create[IO, IO](
        accountStorage,
        valuationService,
        valuations,
        snapshots,
        targets,
        dispatcher,
        systemAlerts,
        fastConfig,
        channels = List("test")
      )
    }

  "AnalyticsProcessImpl" >> {
    "run" >> {

      "recomputes the trailing window and upserts it for every account" >> {
        val series1 = ValuationSeries(accountId1, rub, Vector.empty, Set.empty, Set.empty)
        val series2 = ValuationSeries(accountId2, rub, Vector.empty, Set.empty, Set.empty)

        for {
          accounts <- AnalyticsFakes.accountStorage(
            List(account(accountId1, "one"), account(accountId2, "two"))
          )
          valuationService <- AnalyticsFakes.portfolioValuationService(
            Map(accountId1 -> IO.pure(series1), accountId2 -> IO.pure(series2))
          )
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          calls <- valuationService.calls
          stored <- valuations.all
        } yield {
          calls must contain(accountId1, accountId2)
          stored.keySet must_== Set(accountId1, accountId2)
        }
      }

      "continues with the next account when one account's valuation fails" >> {
        val goodSeries = ValuationSeries(accountId2, rub, Vector.empty, Set.empty, Set.empty)

        for {
          accounts <- AnalyticsFakes.accountStorage(
            List(account(accountId1, "one"), account(accountId2, "two"))
          )
          valuationService <- AnalyticsFakes.portfolioValuationService(
            Map(
              accountId1 -> IO.raiseError(new RuntimeException("boom")),
              accountId2 -> IO.pure(goodSeries)
            )
          )
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          calls <- valuationService.calls
          stored <- valuations.all
        } yield {
          calls must contain(accountId1, accountId2)
          stored.keySet must_== Set(accountId2)
        }
      }

      // interruptAfter bounds wall-clock time instead of counting emitted elements, since a
      // first-attempt failure here never lets Stream#take's early termination fire.
      "raises a process-crashed:analytics system alert and keeps retrying when the account list can't be loaded" >> {
        for {
          accounts <- AnalyticsFakes.accountStorageF(IO.raiseError(new RuntimeException("db down")))
          valuationService <- AnalyticsFakes.portfolioValuationService()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          alerts <- AlertFakes.systemAlerts()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher,
            alerts
          )
          _ <- process.run.interruptAfter(300.millis).compile.drain
          raised <- alerts.raised
        } yield raised.map(_.name) must contain("process-crashed:analytics")
      }

      "dispatches one AllocationDrift alert per breached target after an account's valuation upsert" >> {
        val series = ValuationSeries(accountId1, rub, Vector.empty, Set.empty, Set.empty)
        // 100% in "share", 0% cash: a target of 50% is breached (drift 50pp > 5pp threshold), a
        // target of 100% is not (drift 0pp).
        val snapshot = PositionSnapshot(
          LocalDate.now,
          rub,
          Vector(position("share", BigDecimal("1.0"), currency = Some("USD"))),
          cash = BigDecimal(0),
          total = BigDecimal(1000),
          dayChange = BigDecimal(0),
          unpriced = 0
        )
        val breached = AnalyticsFakes.allocationTarget(
          accountId1,
          Dimension.InstrumentType,
          "share",
          targetPct = BigDecimal(50),
          driftThresholdPct = BigDecimal(5)
        )
        // Actual currency weight is also 100% USD, exactly matching this target's own 100% - drift
        // 0pp, not breached - alongside the breached instrument-type target above.
        val withinThreshold = AnalyticsFakes.allocationTarget(
          accountId1,
          Dimension.Currency,
          "USD",
          targetPct = BigDecimal(100),
          driftThresholdPct = BigDecimal(5)
        )

        for {
          accounts <- AnalyticsFakes.accountStorage(List(account(accountId1, "one")))
          valuationService <- AnalyticsFakes.portfolioValuationService(
            outcomes = Map(accountId1 -> IO.pure(series)),
            positionSnapshots = Map(accountId1 -> IO.pure(snapshot))
          )
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage(List(breached, withinThreshold))
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          dispatched <- dispatcher.dispatched
        } yield {
          dispatched must have size 1
          val alert = dispatched.head
          alert.kind must_== AlertKind.AllocationDrift
          alert.ruleKey must_== "drift:1:instrument_type:share"
        }
      }

      "dispatches no alert when every target is within its drift threshold" >> {
        val series = ValuationSeries(accountId1, rub, Vector.empty, Set.empty, Set.empty)
        val snapshot = PositionSnapshot(
          LocalDate.now,
          rub,
          Vector(position("share", BigDecimal("0.5"))),
          cash = BigDecimal(500),
          total = BigDecimal(1000),
          dayChange = BigDecimal(0),
          unpriced = 0
        )
        val onTarget = AnalyticsFakes.allocationTarget(
          accountId1,
          Dimension.InstrumentType,
          "share",
          targetPct = BigDecimal(50),
          driftThresholdPct = BigDecimal(5)
        )

        for {
          accounts <- AnalyticsFakes.accountStorage(List(account(accountId1, "one")))
          valuationService <- AnalyticsFakes.portfolioValuationService(
            outcomes = Map(accountId1 -> IO.pure(series)),
            positionSnapshots = Map(accountId1 -> IO.pure(snapshot))
          )
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage(List(onTarget))
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          dispatched <- dispatcher.dispatched
        } yield dispatched must beEmpty
      }

      "saves a snapshot of the holdings as of today for every account" >> {
        for {
          before <- UtcClock.todayUtc[IO]
          accounts <- AnalyticsFakes.accountStorage(
            List(account(accountId1, "one"), account(accountId2, "two"))
          )
          valuationService <- AnalyticsFakes.portfolioValuationService()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          after <- UtcClock.todayUtc[IO]
          saved <- snapshots.all
        } yield {
          saved.keySet.map { case (accountId, _) => accountId } must_== Set(accountId1, accountId2)
          // The fake valuation service dates the holdings with the date it is asked for, and the
          // run asked for "today", read once between the two reads here.
          (saved.values.map(_.holdings.date).toSet -- Set(before, after)) must beEmpty
        }
      }

      "gives each snapshot the figures of every period over the account's stored valuations" >> {
        for {
          today <- UtcClock.todayUtc[IO]
          series = ValuationSeries(
            accountId1,
            rub,
            Vector(
              point(today.minusDays(40), 1000),
              point(today.minusDays(10), 1100),
              point(today, 1210)
            ),
            Set.empty,
            Set.empty
          )
          accounts <- AnalyticsFakes.accountStorage(
            List(account(accountId1, "one"), account(accountId2, "two"))
          )
          valuationService <- AnalyticsFakes.portfolioValuationService(
            Map(
              accountId1 -> IO.pure(series),
              accountId2 -> IO
                .pure(ValuationSeries(accountId2, rub, Vector.empty, Set.empty, Set.empty))
            )
          )
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          saved <- snapshots.all
        } yield {
          val metrics = saved.map { case ((accountId, _), snapshot) =>
            accountId -> snapshot.metrics
          }
          // A month reaches back at most 31 days, so only the longer periods include the point 40
          // days back, 1000. The month: 1210 / 1100 - 1 = 0.1. The others: 1210 / 1000 - 1 = 0.21.
          metrics(accountId1).view.mapValues(_.totalReturn).toMap must_== Map(
            Period.OneMonth -> BigDecimal("0.1"),
            Period.ThreeMonths -> BigDecimal("0.21"),
            Period.SixMonths -> BigDecimal("0.21"),
            Period.OneYear -> BigDecimal("0.21"),
            Period.All -> BigDecimal("0.21")
          )
          // The second account has no valuation point, so no period has figures.
          metrics(accountId2) must beEmpty
        }
      }

      "checks drift on the holdings it saved in the snapshot" >> {
        // 100% in "share" against a target of 50%: breached.
        val concentrated = PositionSnapshot(
          LocalDate.of(2026, 11, 1),
          rub,
          Vector(position("share", BigDecimal("1.0"))),
          cash = BigDecimal(0),
          total = BigDecimal(1000),
          dayChange = BigDecimal(0),
          unpriced = 0
        )
        // Half in "share": on target, so a drift check on these holdings raises nothing.
        val balanced = concentrated.copy(
          positions = Vector(position("share", BigDecimal("0.5"))),
          cash = BigDecimal(500)
        )
        val target = AnalyticsFakes.allocationTarget(
          accountId1,
          Dimension.InstrumentType,
          "share",
          targetPct = BigDecimal(50),
          driftThresholdPct = BigDecimal(5)
        )

        for {
          // The holdings are concentrated when first read and balanced on any later read, as if a
          // trade had landed in between.
          readBefore <- Ref.of[IO, Boolean](false)
          accounts <- AnalyticsFakes.accountStorage(List(account(accountId1, "one")))
          valuationService <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(
              accountId1 -> readBefore
                .getAndSet(true)
                .map(read => if (read) balanced else concentrated)
            )
          )
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.portfolioSnapshotStorage()
          targets <- AnalyticsFakes.allocationTargetStorage(List(target))
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          saved <- snapshots.all
          dispatched <- dispatcher.dispatched
        } yield {
          saved.values.map(_.holdings).toList must_== List(concentrated)
          dispatched.map(_.ruleKey) must_== List("drift:1:instrument_type:share")
        }
      }

      "continues with the next account when one account's snapshot fails to save" >> {
        for {
          accounts <- AnalyticsFakes.accountStorage(
            List(account(accountId1, "one"), account(accountId2, "two"))
          )
          valuationService <- AnalyticsFakes.portfolioValuationService()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          snapshots <- AnalyticsFakes.failingPortfolioSnapshotStorage(
            accountId1,
            new RuntimeException("boom")
          )
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
            snapshots,
            targets,
            dispatcher
          )
          _ <- process.run.take(1).compile.drain
          saved <- snapshots.all
        } yield saved.keySet.map { case (accountId, _) => accountId } must_== Set(accountId2)
      }
    }
  }
}
