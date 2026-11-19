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
import st.btu.trading.pillar.gateways.binance.BinanceApiConfig
import st.btu.trading.pillar.models.binance.{ApiKey, BinanceAccount, SecretKey}
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.processes.BinanceSyncProcessImpl.AccountSync
import st.btu.trading.pillar.security.{Bouncy, CryptoImpl}
import st.btu.trading.pillar.services.{BinanceSyncConfig, BinanceSyncService}
import st.btu.trading.pillar.testkit.AlertFakes.FakeSystemAlerts
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{AlertFakes, BinanceFakes, MarketDataFakes}
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZoneOffset}
import scala.concurrent.duration.*

class BinanceSyncProcessImplSpec extends Specification with CatsEffect {

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
    alerts: FakeSystemAlerts
  ) {

    // A service that records its calls, then does `onRebuild` or `onSync`.
    def service(
      owner: AccountId,
      onRebuild: IO[Unit] = IO.unit,
      onSync: IO[Unit] = IO.unit
    ): AccountSync[IO] =
      AccountSync(
        owner,
        new BinanceSyncService[IO] {
          def sync: IO[Unit] = record(owner, Request.Sync) >> onSync

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
        Logging.Make.plain[IO].forService[BinanceSyncProcess[IO]]
      def untilEnough: IO[Unit] = enough.ifM(IO.unit, IO.sleep(PollEvery) >> untilEnough)
      val process = new BinanceSyncProcessImpl[IO](accounts, alerts, FastInterval)
      IO.race(process.run.compile.drain, untilEnough).void.timeout(Patience)
    }
  }

  private def harness: IO[Harness] =
    for {
      local <- MarketDataFakes.withFiberLocal(ProcessContext("init", AccountId(0)))
      calls <- Ref.of[IO, List[Call]](List.empty)
      alerts <- AlertFakes.systemAlerts()
    } yield Harness(local, calls, alerts)

  private val boom = new RuntimeException("boom")

  "BinanceSyncProcessImpl" >> {
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

      "records the records stored for an account again, once, before its first sync" >> {
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
          } yield raised.map(_.name).distinct must_== List("process-crashed:binance-sync:1")
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

      "when recording an account's stored records again fails" >> {
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
            raised.map(_.name) must_== List("process-crashed:binance-sync:1")
          }
        }
      }
    }

    "resource" >> {
      "reads every account through one set of per-IP limits, so that their calls are paced together" >> {
        implicit val bouncy: Bouncy = new Bouncy {}
        val accounts = List(
          BinanceAccount(first, ApiKey("key-one"), SecretKey("secret-one")),
          BinanceAccount(second, ApiKey("key-two"), SecretKey("secret-two"))
        )
        // A read of trades has 600 ms * 20 / (6000 * 0.00667) = 300 ms to itself on /api/v3.
        val spacing = 300.millis
        val limits = BinanceApiConfig(
          limitWindow = 600.millis,
          ipBudget = 0.00667,
          retryDelay = 1.milli,
          rateLimitDelay = 1.milli
        )
        val fiatEmpty = """{"code":"000000","message":"success","success":true}"""
        def answer(path: String): String =
          path match {
            case "/api/v3/exchangeInfo" =>
              """{"symbols":[{"symbol":"BTCUSDT","baseAsset":"BTC","quoteAsset":"USDT"}]}"""
            case "/sapi/v1/account/apiRestrictions" =>
              """{"enableReading":true,"ipRestrict":true}"""
            case "/sapi/v1/fiat/orders" | "/sapi/v1/fiat/payments" => fiatEmpty
            case "/sapi/v1/convert/tradeFlow" => """{"list":[],"moreData":false}"""
            case _ => "[]"
          }
        for {
          h <- harness
          tradeTimes <- Ref.of[IO, List[FiniteDuration]](List.empty)
          records <- BinanceFakes.recordStorage()
          pairs <- BinanceFakes.pairStorage()
          prices <- BinanceFakes.minutePriceStorage()
          instruments <- MarketDataFakes.brokerInstrumentStorage()
          yesterday <- IO.realTimeInstant.map(LocalDate.ofInstant(_, ZoneOffset.UTC).minusDays(1))
          backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest.thenRespondF { request =>
            val path = "/" + request.uri.path.mkString("/")
            val time = path match {
              case "/api/v3/time" => IO.realTime.map(now => s"""{"serverTime":${now.toMillis}}""")
              case "/api/v3/myTrades" =>
                IO.monotonic.flatMap(now => tradeTimes.update(_ :+ now)).as(answer(path))
              case _ => IO.pure(answer(path))
            }
            time.map(Response(_, StatusCode.Ok))
          }
          _ <- {
            implicit val withLocal: WithLocal[IO, ProcessContext] = h.local
            implicit val genUUID: GenUUID[IO] = GenUUID.syncGenUUID[IO]
            implicit val logs: Logs[IO, IO] =
              Logs.const[IO, IO](Logging.Make.plain[IO].forService[BinanceSyncProcess[IO]])
            def untilBothRead: IO[Unit] =
              tradeTimes.get.map(_.size >= 2).ifM(IO.unit, IO.sleep(PollEvery) >> untilBothRead)
            BinanceSyncProcessImpl
              .resource[IO, IO](
                accounts,
                BinanceSyncConfig(historyStart = yesterday),
                1.hour,
                records,
                pairs,
                prices,
                instruments,
                h.alerts,
                CryptoImpl.create[IO],
                backend,
                limits
              )
              .use(process => IO.race(process.run.compile.drain, untilBothRead))
              .timeout(Patience)
          }
          reads <- tradeTimes.get.map(_.sorted)
          raised <- h.alerts.raised
        } yield {
          reads.size must be_>=(2)
          // Apart in time, where a client of its own limits for each account would send the two
          // reads together.
          (reads(1) - reads.head) must be_>=(spacing / 2)
          raised must beEmpty
        }
      }
    }
  }
}
