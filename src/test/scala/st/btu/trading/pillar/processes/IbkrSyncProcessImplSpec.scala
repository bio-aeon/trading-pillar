package st.btu.trading.pillar.processes

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref}
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import org.specs2.mutable.Specification
import sttp.client3.Response
import sttp.client3.impl.cats.implicits.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.gateways.ibkr.FlexWebServiceConfig
import st.btu.trading.pillar.models.domain.{
  AccountId,
  AccountOperation,
  BrokerInstrumentId,
  OperationKind
}
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.processes.IbkrSyncProcessImpl.AccountSync
import st.btu.trading.pillar.services.{IbkrReconciler, IbkrSyncConfig, IbkrSyncService}
import st.btu.trading.pillar.storages.AccountOperationStorage
import st.btu.trading.pillar.testkit.AlertFakes.FakeSystemAlerts
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.IbkrFakes.{element, statementXml}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{AlertFakes, AnalyticsFakes, IbkrFakes, MarketDataFakes}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}

import java.time.{Instant, LocalDate, ZoneId}
import scala.concurrent.duration.*

class IbkrSyncProcessImplSpec extends Specification with CatsEffect {

  private val first = AccountId(1)
  private val second = AccountId(2)

  private val FastInterval = 10.millis
  private val PollEvery = 5.millis
  private val Patience = 20.seconds

  // What a service was asked to do: by which account's service, in the context of which account,
  // and what.
  private enum Request {
    case Rebuild, Sync
  }

  private final case class Call(owner: AccountId, contextAccount: AccountId, request: Request)

  private final case class Harness(
    local: WithLocal[IO, ProcessContext],
    calls: Ref[IO, List[Call]],
    alerts: FakeSystemAlerts,
    operations: AccountOperationStorage[IO]
  ) {

    // A service that records its calls, then does `onRebuild` or `onSync`. Its sync reads no
    // window unless `onSync` says what the last window reports.
    def service(
      owner: AccountId,
      onRebuild: IO[Unit] = IO.unit,
      onSync: IO[Option[IbkrOpenPositions]] = IO.none
    ): AccountSync[IO] =
      AccountSync(
        owner,
        new IbkrSyncService[IO] {
          def sync: IO[Option[IbkrOpenPositions]] = record(owner, Request.Sync) >> onSync

          def rebuildTranslations: IO[Unit] = record(owner, Request.Rebuild) >> onRebuild
        }
      )

    private def record(owner: AccountId, request: Request): IO[Unit] =
      local.context.flatMap(context => calls.update(_ :+ Call(owner, context.accountId, request)))

    def callsOf(owner: AccountId): IO[List[Request]] =
      calls.get.map(_.filter(_.owner == owner).map(_.request))

    def syncsOf(owner: AccountId): IO[Int] =
      callsOf(owner).map(_.count(_ == Request.Sync))

    // Runs the process over `accounts` until `enough` holds, or until it ends by itself. The spec
    // fails if neither happens within `Patience`.
    def run(accounts: List[AccountSync[IO]], enough: IO[Boolean] = IO.pure(false)): IO[Unit] = {
      implicit val withLocal: WithLocal[IO, ProcessContext] = local
      implicit val genUUID: GenUUID[IO] = GenUUID.syncGenUUID[IO]
      implicit val logging: Logging[IO] =
        Logging.Make.plain[IO].forService[IbkrSyncProcess[IO]]
      implicit val logs: Logs[IO, IO] = Logs.const[IO, IO](logging)
      def untilEnough: IO[Unit] = enough.ifM(IO.unit, IO.sleep(PollEvery) >> untilEnough)
      for {
        instruments <- MarketDataFakes.brokerInstrumentStorage()
        reconciler <- IbkrReconciler.create[IO, IO](operations, instruments, alerts)
        process = new IbkrSyncProcessImpl[IO](accounts, reconciler, alerts, FastInterval)
        _ <- IO.race(process.run.compile.drain, untilEnough).void.timeout(Patience)
      } yield ()
    }
  }

  // A harness whose accounts have recorded the operations in `operations`.
  private def harnessOver(operations: AccountOperationStorage[IO]): IO[Harness] =
    for {
      local <- MarketDataFakes.withFiberLocal(ProcessContext("init", AccountId(0)))
      calls <- Ref.of[IO, List[Call]](List.empty)
      alerts <- AlertFakes.systemAlerts()
    } yield Harness(local, calls, alerts, operations)

  private def harness: IO[Harness] =
    AnalyticsFakes.accountOperationStorage().flatMap(harnessOver)

  private val boom = new RuntimeException("boom")

  "IbkrSyncProcessImpl" >> {
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

      "records the rows stored for an account again, once, before its first sync" >> {
        for {
          h <- harness
          _ <- h.run(List(h.service(first)), enough = h.syncsOf(first).map(_ >= 2))
          requests <- h.callsOf(first)
        } yield {
          requests.headOption must beSome[Request](Request.Rebuild)
          requests.count(_ == Request.Rebuild) must_== 1
        }
      }

      "syncs an account again after each run" >> {
        for {
          h <- harness
          _ <- h.run(List(h.service(first)), enough = h.syncsOf(first).map(_ >= 3))
          syncs <- h.syncsOf(first)
        } yield syncs must be_>=(3)
      }

      "when an account's sync fails" >> {
        "raises a system alert named after that account only, and keeps syncing the others" >> {
          for {
            h <- harness
            _ <- h.run(
              List(h.service(first, onSync = IO.raiseError(boom)), h.service(second)),
              enough = (h.alerts.raised.map(_.nonEmpty), h.syncsOf(second)).mapN(_ && _ >= 2)
            )
            raised <- h.alerts.raised
          } yield raised.map(_.name).distinct must_== List("process-crashed:ibkr-sync:1")
        }

        "starts that account's sync again after the interval" >> {
          for {
            h <- harness
            _ <- h.run(
              List(h.service(first, onSync = IO.raiseError(boom))),
              enough = h.syncsOf(first).map(_ >= 2)
            )
            syncs <- h.syncsOf(first)
          } yield syncs must be_>=(2)
        }
      }

      "when recording an account's stored rows again fails" >> {
        "tries again, and syncs the account only once it has succeeded" >> {
          for {
            h <- harness
            attempts <- Ref.of[IO, Int](0)
            failFirst = attempts
              .getAndUpdate(_ + 1)
              .flatMap(n => if (n == 0) IO.raiseError(boom) else IO.unit)
            _ <- h.run(
              List(h.service(first, onRebuild = failFirst)),
              enough = h.syncsOf(first).map(_ >= 1)
            )
            requests <- h.callsOf(first)
            raised <- h.alerts.raised
          } yield {
            requests.take(3) must_== List(Request.Rebuild, Request.Rebuild, Request.Sync)
            raised.map(_.name) must_== List("process-crashed:ibkr-sync:1")
          }
        }
      }
    }

    "reconciliation" >> {
      // IB reports 10 shares of contract 12345 as open at the end of 8 November.
      val reported = IbkrOpenPositions(
        LocalDate.of(2026, 11, 8),
        ZoneId.of("America/New_York"),
        List(
          IbkrOpenPosition(
            BrokerInstrumentId("12345"),
            Some(Ticker("VOD")),
            Some("STK"),
            BigDecimal(10)
          )
        )
      )

      "follows a sync that read a window, and raises what differs, named after the account" >> {
        for {
          h <- harness
          _ <- h.run(
            List(h.service(first, onSync = IO.pure(Some(reported)))),
            enough = h.syncsOf(first).map(_ >= 2)
          )
          raised <- h.alerts.raised
        } yield raised.map(_.name) must_== List("ibkr-reconciliation:1")
      }

      "is left out after a sync that read no window, even for an account that holds something" >> {
        val held = accountOperation(
          first,
          OperationKind.Buy,
          -100,
          Instant.parse("2026-11-05T15:00:00Z"),
          quantity = 10
        ).copy(instrument = Some(IbkrFakes.instrumentKey("12345")))
        for {
          storage <- AnalyticsFakes.accountOperationStorage(List(held))
          h <- harnessOver(storage)
          _ <- h.run(List(h.service(first)), enough = h.syncsOf(first).map(_ >= 3))
          raised <- h.alerts.raised
        } yield raised must beEmpty
      }

      "that fails is a failure of the account's loop, like a failure of its sync" >> {
        val unreadable = new AccountOperationStorage[IO] {
          def operations(
            accountId: AccountId,
            since: Instant,
            until: Instant
          ): IO[Vector[AccountOperation]] = IO.raiseError(boom)
        }
        for {
          h <- harnessOver(unreadable)
          _ <- h.run(
            List(h.service(first, onSync = IO.pure(Some(reported)))),
            enough = h.syncsOf(first).map(_ >= 2)
          )
          raised <- h.alerts.raised
        } yield raised.map(_.name).distinct must_== List("process-crashed:ibkr-sync:1")
      }
    }

    "resource" >> {
      "reads every account through one client, so that their calls are paced together" >> {
        val spacing = 300.millis
        val ibkrAccounts = List(
          IbkrAccount(first, IbkrAccountId("U1111111"), FlexQueryId("q1")),
          IbkrAccount(second, IbkrAccountId("U2222222"), FlexQueryId("q2"))
        )
        // Each query has the statement of its own account, with a dividend of its own.
        val statements = Map(
          "q1" -> statementXml("U1111111", "20250101", "20251231", dividend("c1")),
          "q2" -> statementXml("U2222222", "20250101", "20251231", dividend("c2"))
        )
        for {
          h <- harness
          times <- Ref.of[IO, List[FiniteDuration]](List.empty)
          storage <- IbkrFakes.statementStorage()
          instruments <- MarketDataFakes.brokerInstrumentStorage()
          backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest.thenRespondF { request =>
            val query = request.uri.paramsMap.getOrElse("q", "")
            val body =
              if (request.uri.path.lastOption.contains("SendRequest")) accepted(query)
              else statements(query)
            IO.monotonic.flatMap(now => times.update(_ :+ now)).as(Response(body, StatusCode.Ok))
          }
          _ <- {
            implicit val withLocal: WithLocal[IO, ProcessContext] = h.local
            implicit val genUUID: GenUUID[IO] = GenUUID.syncGenUUID[IO]
            implicit val logs: Logs[IO, IO] =
              Logs.const[IO, IO](Logging.Make.plain[IO].forService[IbkrSyncProcess[IO]])
            def untilBothStored: IO[Unit] =
              storage.storedRows
                .map(_.map(_.accountId).toSet == Set(first, second))
                .ifM(IO.unit, IO.sleep(PollEvery) >> untilBothStored)
            IbkrSyncProcessImpl
              .resource[IO, IO](
                ibkrAccounts,
                FlexToken("token"),
                // From 1 January four years back, which is never after yesterday.
                IbkrSyncConfig(windowDays = 5000),
                1.hour,
                storage,
                storage,
                instruments,
                h.alerts,
                backend,
                FlexWebServiceConfig(minSpacing = spacing)
              )
              .use(process => IO.race(process.run.compile.drain, untilBothStored))
              .timeout(Patience)
          }
          calls <- times.get.map(_.sorted)
          raised <- h.alerts.raised
        } yield {
          calls.size must_== 4
          // Apart in time, where a client of its own for each account would send the two requests
          // together.
          calls.zip(calls.drop(1)).map { case (earlier, later) => later - earlier }.min must
            be_>=(spacing / 2)
          raised must beEmpty
        }
      }
    }
  }

  private def dividend(id: String): String =
    element(
      "CashTransaction",
      "type" -> "Dividends",
      "amount" -> "5",
      "currency" -> "USD",
      "transactionID" -> id,
      "reportDate" -> "20250105",
      "levelOfDetail" -> "DETAIL"
    )

  // The answer to a request that makes `reference` the reference code of the statement.
  private def accepted(reference: String): String =
    s"<FlexStatementResponse><Status>Success</Status><ReferenceCode>$reference</ReferenceCode></FlexStatementResponse>"
}
