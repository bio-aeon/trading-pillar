package st.btu.trading.pillar.services

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.models.binance.{BinanceAsset, BinanceBalances}
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.testkit.AlertFakes.{FakeSystemAlerts, RaisedSystemAlert}
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.BinanceFakes.{asset, at, pairOf}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{AlertFakes, AnalyticsFakes}
import tofu.logging.{Logging, Logs}

import java.time.Instant

class BinanceReconcilerSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[BinanceReconciler[IO]])

  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)

  private val asOf = at("2026-03-10T12:00:00Z")
  private val earlier = at("2026-03-09T10:00:00Z")

  private val cashAssets = Set(asset("USDT"), asset("USDC"))
  private val listed = List(pairOf("BTC", "USDT"), pairOf("ETH", "BTC"), pairOf("BNB", "USDT"))

  // `quantity` units of the coin bought, or sold when it is negative.
  private def moved(
    code: String,
    quantity: BigDecimal,
    at: Instant = earlier,
    owner: AccountId = accountId
  ): AccountOperation = {
    val kind = if (quantity.signum > 0) OperationKind.Buy else OperationKind.Sell
    accountOperation(owner, kind, 0, at, quantity = quantity)
      .copy(instrument = Some(InstrumentKey(Broker.Binance, BrokerInstrumentId(code))))
  }

  // `amount` of cash in the currency paid in, or out when it is negative.
  private def paid(
    currency: String,
    amount: BigDecimal,
    at: Instant = earlier,
    owner: AccountId = accountId
  ): AccountOperation =
    accountOperation(owner, OperationKind.Deposit, amount, at, currency = currency)

  private def reported(held: (String, BigDecimal)*): BinanceBalances =
    BinanceBalances(held.map { case (code, amount) => asset(code) -> amount }.toMap, Map.empty)

  private def reconcilerOver(
    operations: List[AccountOperation],
    alerts: SystemAlerts[IO]
  ): IO[BinanceReconciler[IO]] =
    AnalyticsFakes
      .accountOperationStorage(operations)
      .flatMap(BinanceReconciler.create[IO, IO](_, alerts, cashAssets))

  private final case class Fixture(reconciler: BinanceReconciler[IO], alerts: FakeSystemAlerts) {
    def reconcile(
      balances: BinanceBalances,
      account: AccountId = accountId,
      at: Instant = asOf
    ): IO[Unit] =
      reconciler.reconcile(account, balances, listed, at)

    def differingCoins(
      balances: BinanceBalances,
      account: AccountId = accountId,
      at: Instant = asOf
    ): IO[Set[BinanceAsset]] =
      reconciler.differingCoins(account, balances, listed, at)

    def raised: IO[List[RaisedSystemAlert]] = alerts.raised
  }

  private def fixture(operations: List[AccountOperation] = Nil): IO[Fixture] =
    for {
      alerts <- AlertFakes.systemAlerts()
      reconciler <- reconcilerOver(operations, alerts)
    } yield Fixture(reconciler, alerts)

  private def lines(alerts: List[RaisedSystemAlert]): List[String] =
    alerts.flatMap(_.body.linesIterator.toList)

  "BinanceReconciler" >> {
    "differingCoins" >> {
      "gives the coins whose balance differs from the recorded position, and no cash asset" >> {
        for {
          f <- fixture(List(moved("BTC", 1), moved("ETH", 2), paid("USDT", 100)))
          coins <- f.differingCoins(reported("BTC" -> BigDecimal("0.9"), "ETH" -> 2, "USDT" -> 99))
        } yield coins must_== Set(asset("BTC"))
      }

      "gives none when every balance matches" >> {
        for {
          f <- fixture(List(moved("BTC", 1), paid("USDT", 100)))
          coins <- f.differingCoins(reported("BTC" -> 1, "USDT" -> 100))
        } yield coins must beEmpty
      }

      "gives a coin that only Binance reports, and one that only the account recorded" >> {
        for {
          f <- fixture(List(moved("ETH", 2)))
          coins <- f.differingCoins(reported("DOGE" -> 5))
        } yield coins must_== Set(asset("DOGE"), asset("ETH"))
      }

      "leaves out a Simple Earn token" >> {
        for {
          f <- fixture()
          coins <- f.differingCoins(reported("LDBTC" -> 5))
        } yield coins must beEmpty
      }

      "counts only the operations that occurred before the instant the balances were read" >> {
        val operations = List(moved("BTC", 1, earlier), moved("BTC", 4, asOf))
        for {
          f <- fixture(operations)
          matching <- f.differingCoins(reported("BTC" -> 1))
          later <- f.differingCoins(reported("BTC" -> 5))
        } yield (matching, later) must_== (Set.empty, Set(asset("BTC")))
      }

      "counts the operations of the account only" >> {
        for {
          f <- fixture(List(moved("BTC", 1, owner = otherAccountId)))
          coins <- f.differingCoins(reported())
        } yield coins must beEmpty
      }

      "leaves out a coin whose difference is the one the last reconciliation found" >> {
        for {
          f <- fixture(List(moved("BTC", 1)))
          _ <- f.reconcile(reported("BTC" -> 2))
          coins <- f.differingCoins(reported("BTC" -> 2))
        } yield coins must beEmpty
      }

      "gives a coin again once its difference is not the one the last reconciliation found" >> {
        for {
          f <- fixture(List(moved("BTC", 1)))
          _ <- f.reconcile(reported("BTC" -> 2))
          coins <- f.differingCoins(reported("BTC" -> 3))
        } yield coins must_== Set(asset("BTC"))
      }

      "raises nothing, however much differs" >> {
        for {
          f <- fixture(List(moved("BTC", 1)))
          _ <- f.differingCoins(reported("BTC" -> 2))
          raised <- f.raised
        } yield raised must beEmpty
      }
    }

    "reconcile" >> {
      "differences" >> {
        "are raised as binance-reconciliation:<account id>, naming each asset, both amounts and the instant" >> {
          for {
            f <- fixture(List(moved("BTC", 1), paid("USDT", 100)))
            _ <- f.reconcile(reported("BTC" -> BigDecimal("0.9"), "USDT" -> 101))
            raised <- f.raised
          } yield raised must_== List(
            RaisedSystemAlert(
              "binance-reconciliation:1",
              "Binance balances differ: account 1",
              "Recorded balances of account 1 differ from the balances Binance reports as of " +
                "2026-03-10T12:00:00Z:\nBTC: recorded 1, Binance 0.9\nUSDT: recorded 100, Binance 101"
            )
          )
        }

        "name an asset that only Binance reports as recorded at zero, and one only the account holds as reported at zero" >> {
          for {
            f <- fixture(List(moved("ETH", 2)))
            _ <- f.reconcile(reported("DOGE" -> 5))
            raised <- f.raised
          } yield lines(raised) must contain(
            allOf("DOGE: recorded 0, Binance 5", "ETH: recorded 2, Binance 0")
          )
        }

        "print amounts without trailing zeros" >> {
          for {
            f <- fixture(List(moved("BTC", BigDecimal("12.500000000000000000"))))
            _ <- f.reconcile(reported("BTC" -> 12))
            raised <- f.raised
          } yield lines(raised) must contain("BTC: recorded 12.5, Binance 12")
        }

        "are not raised while the recorded and the reported balances match" >> {
          for {
            f <- fixture(List(moved("BTC", 1)))
            _ <- f.reconcile(reported("BTC" -> 1))
            raised <- f.raised
          } yield raised must beEmpty
        }

        "leave out the operations that occurred after the instant the balances were read" >> {
          for {
            f <- fixture(List(moved("BTC", 1), moved("BTC", 4, at = asOf.plusSeconds(1))))
            _ <- f.reconcile(reported("BTC" -> 1))
            raised <- f.raised
          } yield raised must beEmpty
        }

        "are raised once while they stay the same, whenever they were read" >> {
          for {
            f <- fixture(List(moved("BTC", 1)))
            _ <- f.reconcile(reported("BTC" -> 2))
            _ <- f.reconcile(reported("BTC" -> 2), at = asOf.plusSeconds(3600))
            raised <- f.raised
          } yield raised.map(_.name) must_== List("binance-reconciliation:1")
        }

        "are raised again when they change" >> {
          for {
            f <- fixture(List(moved("BTC", 1)))
            _ <- f.reconcile(reported("BTC" -> 2))
            _ <- f.reconcile(reported("BTC" -> 3))
            raised <- f.raised
          } yield {
            raised.map(_.name) must_== List("binance-reconciliation:1", "binance-reconciliation:1")
            lines(raised).filter(_.startsWith("BTC")) must_== List(
              "BTC: recorded 1, Binance 2",
              "BTC: recorded 1, Binance 3"
            )
          }
        }

        "are raised again when they come back after clearing" >> {
          for {
            f <- fixture(List(moved("BTC", 1)))
            _ <- f.reconcile(reported("BTC" -> 2))
            _ <- f.reconcile(reported("BTC" -> 1))
            _ <- f.reconcile(reported("BTC" -> 2))
            raised <- f.raised
          } yield raised.map(_.name) must_== List(
            "binance-reconciliation:1",
            "binance-reconciled:1",
            "binance-reconciliation:1"
          )
        }

        "are raised again at the next reconciliation when raising them failed" >> {
          for {
            recorded <- AlertFakes.systemAlerts()
            attempts <- Ref.of[IO, Int](0)
            flaky = new SystemAlerts[IO] {
              def raise(name: String, title: String, body: String): IO[Unit] =
                attempts
                  .getAndUpdate(_ + 1)
                  .flatMap(n =>
                    if (n == 0) IO.raiseError(new RuntimeException("alert channel down"))
                    else recorded.raise(name, title, body)
                  )
            }
            reconciler <- reconcilerOver(List(moved("BTC", 1)), flaky)
            first <- reconciler.reconcile(accountId, reported("BTC" -> 2), listed, asOf).attempt
            _ <- reconciler.reconcile(accountId, reported("BTC" -> 2), listed, asOf)
            raised <- recorded.raised
          } yield {
            first must beLeft[Throwable]
            raised.map(_.name) must_== List("binance-reconciliation:1")
          }
        }

        "are kept apart for each account" >> {
          val operations = List(moved("BTC", 1), moved("BTC", 1, owner = otherAccountId))
          for {
            f <- fixture(operations)
            _ <- f.reconcile(reported("BTC" -> 2), accountId)
            _ <- f.reconcile(reported("BTC" -> 2), otherAccountId)
            raised <- f.raised
          } yield raised.map(_.name) must_== List(
            "binance-reconciliation:1",
            "binance-reconciliation:2"
          )
        }
      }

      "the all-clear" >> {
        "is raised as binance-reconciled:<account id> once the differences clear, and only once" >> {
          for {
            f <- fixture(List(moved("BTC", 1)))
            _ <- f.reconcile(reported("BTC" -> 2))
            _ <- f.reconcile(reported("BTC" -> 1))
            _ <- f.reconcile(reported("BTC" -> 1))
            raised <- f.raised
          } yield {
            raised.map(_.name) must_== List("binance-reconciliation:1", "binance-reconciled:1")
            raised.last must_== RaisedSystemAlert(
              "binance-reconciled:1",
              "Binance balances match: account 1",
              "Recorded balances of account 1 match the balances Binance reports as of " +
                "2026-03-10T12:00:00Z."
            )
          }
        }
      }
    }
  }
}
