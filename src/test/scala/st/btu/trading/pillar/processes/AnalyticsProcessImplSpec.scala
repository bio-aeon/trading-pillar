package st.btu.trading.pillar.processes

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.{AlertDispatcher, AlertKind, SystemAlerts}
import st.btu.trading.pillar.analytics.portfolio.*
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.models.Account
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi}
import st.btu.trading.pillar.storages.{
  AccountStorage,
  AllocationTargetStorage,
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
    Account(id, name, ZonedDateTime.now)

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
      quantity = 1,
      price = None,
      value = None,
      weight = Some(weight)
    )

  private def buildProcess(
    accountStorage: AccountStorage[IO],
    valuationService: PortfolioValuationService[IO],
    valuations: PortfolioValuationStorage[IO],
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
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(accounts, valuationService, valuations, targets, dispatcher)
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
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(accounts, valuationService, valuations, targets, dispatcher)
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
          targets <- AnalyticsFakes.allocationTargetStorage()
          dispatcher <- AlertFakes.alertDispatcher()
          alerts <- AlertFakes.systemAlerts()
          process <- buildProcess(
            accounts,
            valuationService,
            valuations,
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
          targets <- AnalyticsFakes.allocationTargetStorage(List(breached, withinThreshold))
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(accounts, valuationService, valuations, targets, dispatcher)
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
          targets <- AnalyticsFakes.allocationTargetStorage(List(onTarget))
          dispatcher <- AlertFakes.alertDispatcher()
          process <- buildProcess(accounts, valuationService, valuations, targets, dispatcher)
          _ <- process.run.take(1).compile.drain
          dispatched <- dispatcher.dispatched
        } yield dispatched must beEmpty
      }
    }
  }
}
