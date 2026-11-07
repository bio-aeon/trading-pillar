package st.btu.trading.pillar.processes

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref}
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import org.specs2.mutable.Specification
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.processes.TcsPortfolioSyncProcessImpl.AccountSync
import st.btu.trading.pillar.services.TcsPortfolioService
import st.btu.trading.pillar.testkit.AlertFakes.FakeSystemAlerts
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{AlertFakes, MarketDataFakes}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.Logging

import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class TcsPortfolioSyncProcessImplSpec extends Specification with CatsEffect {

  private val first = AccountId(1)
  private val second = AccountId(2)

  private val FastInterval = 10.millis
  private val PollEvery = 5.millis
  private val Patience = 20.seconds

  private val startOfSync = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

  // What a service was asked to do: by which account's service, in the context of which account,
  // and what.
  private enum Request {
    case Rebuild
    case Sync(from: Option[ZonedDateTime])
  }

  private final case class Call(owner: AccountId, contextAccount: AccountId, request: Request)

  private final case class Harness(
    local: WithLocal[IO, ProcessContext],
    calls: Ref[IO, List[Call]],
    alerts: FakeSystemAlerts
  ) {

    // A service that records its calls, then does `rebuild` or `sync`. A sync reaches the point a
    // day after the one it began from.
    def service(
      owner: AccountId,
      rebuild: IO[Unit] = IO.unit,
      sync: IO[Unit] = IO.unit
    ): AccountSync[IO] =
      AccountSync(
        owner,
        new TcsPortfolioService[IO] {
          def syncOperations(
            interval: FiniteDuration,
            overlap: FiniteDuration,
            lastDt: Option[ZonedDateTime]
          ): IO[ZonedDateTime] =
            record(owner, Request.Sync(lastDt)) >> sync.as(lastDt.fold(startOfSync)(_.plusDays(1)))

          def rebuildTranslations: IO[Unit] = record(owner, Request.Rebuild) >> rebuild
        }
      )

    private def record(owner: AccountId, request: Request): IO[Unit] =
      local.context.flatMap(context => calls.update(_ :+ Call(owner, context.accountId, request)))

    def callsOf(owner: AccountId): IO[List[Request]] =
      calls.get.map(_.filter(_.owner == owner).map(_.request))

    def syncsOf(owner: AccountId): IO[Int] =
      callsOf(owner).map(_.count(_.isInstanceOf[Request.Sync]))

    // Runs the process over `accounts` until `enough` holds, or until it ends by itself. The spec
    // fails if neither happens within `Patience`.
    def run(accounts: List[AccountSync[IO]], enough: IO[Boolean] = IO.pure(false)): IO[Unit] = {
      implicit val withLocal: WithLocal[IO, ProcessContext] = local
      implicit val genUUID: GenUUID[IO] = GenUUID.syncGenUUID[IO]
      implicit val logging: Logging[IO] =
        Logging.Make.plain[IO].forService[TcsPortfolioSyncProcess[IO]]
      def untilEnough: IO[Unit] = enough.ifM(IO.unit, IO.sleep(PollEvery) >> untilEnough)
      IO.race(
        new TcsPortfolioSyncProcessImpl[IO](accounts, alerts, FastInterval).run.compile.drain,
        untilEnough
      ).void
        .timeout(Patience)
    }
  }

  private def harness: IO[Harness] =
    for {
      local <- MarketDataFakes.withFiberLocal(ProcessContext("init", AccountId(0)))
      calls <- Ref.of[IO, List[Call]](List.empty)
      alerts <- AlertFakes.systemAlerts()
    } yield Harness(local, calls, alerts)

  private val boom = new RuntimeException("boom")

  "TcsPortfolioSyncProcessImpl" >> {
    "run" >> {
      "syncs each account it is given in a context naming that account" >> {
        for {
          h <- harness
          _ <- h.run(
            List(h.service(first), h.service(second)),
            enough = (h.syncsOf(first), h.syncsOf(second)).mapN(_ >= 1 && _ >= 1)
          )
          calls <- h.calls.get
        } yield {
          calls.map(_.owner).toSet must_== Set(first, second)
          calls.filter(call => call.owner != call.contextAccount) must beEmpty
        }
      }

      "syncs nothing when it is given no account" >> {
        for {
          h <- harness
          _ <- h.run(Nil)
          calls <- h.calls.get
        } yield calls must beEmpty
      }

      "records the operations stored for an account again, once, before its first sync" >> {
        for {
          h <- harness
          _ <- h.run(List(h.service(first)), enough = h.syncsOf(first).map(_ >= 2))
          requests <- h.callsOf(first)
        } yield {
          requests.headOption must beSome[Request](Request.Rebuild)
          requests.count(_ == Request.Rebuild) must_== 1
        }
      }

      "continues each sync from the point the previous one reached" >> {
        for {
          h <- harness
          _ <- h.run(List(h.service(first)), enough = h.syncsOf(first).map(_ >= 3))
          requests <- h.callsOf(first)
        } yield requests.collect { case Request.Sync(from) => from }.take(3) must_== List(
          None,
          Some(startOfSync),
          Some(startOfSync.plusDays(1))
        )
      }

      "when an account's sync fails" >> {
        "raises a system alert named after that account only, and keeps syncing the others" >> {
          for {
            h <- harness
            _ <- h.run(
              List(h.service(first, sync = IO.raiseError(boom)), h.service(second)),
              enough = (h.alerts.raised.map(_.nonEmpty), h.syncsOf(second)).mapN(_ && _ >= 2)
            )
            raised <- h.alerts.raised
          } yield raised.map(_.name).distinct must_== List("process-crashed:tcs-portfolio-sync:1")
        }

        "starts that account's sync again after the interval" >> {
          for {
            h <- harness
            _ <- h.run(
              List(h.service(first, sync = IO.raiseError(boom))),
              enough = h.syncsOf(first).map(_ >= 2)
            )
            syncs <- h.syncsOf(first)
          } yield syncs must be_>=(2)
        }
      }

      "when recording an account's stored operations again fails" >> {
        "tries again, and syncs the account only once it has succeeded" >> {
          for {
            h <- harness
            attempts <- Ref.of[IO, Int](0)
            failFirst = attempts
              .getAndUpdate(_ + 1)
              .flatMap(n => if (n == 0) IO.raiseError(boom) else IO.unit)
            _ <- h.run(
              List(h.service(first, rebuild = failFirst)),
              enough = h.syncsOf(first).map(_ >= 1)
            )
            requests <- h.callsOf(first)
            raised <- h.alerts.raised
          } yield {
            requests.take(3) must_== List(Request.Rebuild, Request.Rebuild, Request.Sync(None))
            raised.map(_.name) must_== List("process-crashed:tcs-portfolio-sync:1")
          }
        }
      }
    }
  }
}
