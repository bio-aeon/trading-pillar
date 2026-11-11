package st.btu.trading.pillar.services

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.{IbkrOpenPosition, IbkrOpenPositions}
import st.btu.trading.pillar.models.market.{BrokerInstrument, ClassCode, Ticker}
import st.btu.trading.pillar.testkit.AlertFakes.{FakeSystemAlerts, RaisedSystemAlert}
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{AlertFakes, AnalyticsFakes, IbkrFakes, MarketDataFakes}
import tofu.logging.{Logging, Logs}

import java.time.*

class IbkrReconcilerSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[IbkrReconciler[IO]])

  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)

  // Friday 6 November 2026 in New York, which is five hours behind UTC then: the day ends at 05:00
  // UTC on the 7th.
  private val newYork = ZoneId.of("America/New_York")
  private val day = LocalDate.of(2026, 11, 6)
  private val endOfDay = Instant.parse("2026-11-07T05:00:00Z")
  private val earlier = Instant.parse("2026-11-05T15:00:00Z")

  // `quantity` shares of the contract bought, or sold when it is negative.
  private def moved(
    conid: String,
    quantity: BigDecimal,
    at: Instant = earlier,
    owner: AccountId = accountId
  ): AccountOperation = {
    val kind = if (quantity.signum > 0) OperationKind.Buy else OperationKind.Sell
    accountOperation(owner, kind, 0, at, quantity = quantity)
      .copy(instrument = Some(IbkrFakes.instrumentKey(conid)))
  }

  private def position(conid: String, symbol: String, quantity: BigDecimal): IbkrOpenPosition =
    IbkrOpenPosition(BrokerInstrumentId(conid), Some(Ticker(symbol)), Some("STK"), quantity)

  private def reported(positions: IbkrOpenPosition*): IbkrOpenPositions =
    IbkrOpenPositions(day, newYork, positions.toList)

  private def stored(conid: String, symbol: String): BrokerInstrument =
    BrokerInstrument(
      IbkrFakes.instrumentKey(conid),
      Ticker(symbol),
      Some(ClassCode("LSE")),
      symbol,
      "STK",
      Currency("GBP"),
      ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    )

  private def reconcilerOver(
    operations: List[AccountOperation],
    instruments: List[BrokerInstrument],
    alerts: SystemAlerts[IO]
  ): IO[IbkrReconciler[IO]] =
    for {
      operationStorage <- AnalyticsFakes.accountOperationStorage(operations)
      instrumentStorage <- MarketDataFakes.brokerInstrumentStorage(instruments)
      reconciler <- IbkrReconciler.create[IO, IO](operationStorage, instrumentStorage, alerts)
    } yield reconciler

  private final case class Fixture(reconciler: IbkrReconciler[IO], alerts: FakeSystemAlerts) {
    def reconcile(statement: IbkrOpenPositions, account: AccountId = accountId): IO[Unit] =
      reconciler.reconcile(account, statement)

    def raised: IO[List[RaisedSystemAlert]] = alerts.raised
  }

  private def fixture(
    operations: List[AccountOperation] = Nil,
    instruments: List[BrokerInstrument] = Nil
  ): IO[Fixture] =
    for {
      alerts <- AlertFakes.systemAlerts()
      reconciler <- reconcilerOver(operations, instruments, alerts)
    } yield Fixture(reconciler, alerts)

  private def lines(alerts: List[RaisedSystemAlert]): List[String] =
    alerts.flatMap(_.body.linesIterator.toList)

  "IbkrReconciler" >> {
    "reconcile" >> {
      "recorded positions" >> {
        "count the operations that occurred before the end of the last day in the statement zone" >> {
          // A minute before the end is still the 6th in New York, and the end itself is the 7th.
          val operations =
            List(moved("12345", 10, endOfDay.minusSeconds(60)), moved("12345", 5, endOfDay))
          for {
            f <- fixture(operations)
            _ <- f.reconcile(reported(position("12345", "VOD", 10)))
            raised <- f.raised
          } yield raised must beEmpty
        }
      }

      "differences" >> {
        "are raised as ibkr-reconciliation:<account id>, naming the instrument, its contract and both quantities" >> {
          for {
            f <- fixture(List(moved("12345", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 8)))
            raised <- f.raised
          } yield raised must_== List(
            RaisedSystemAlert(
              "ibkr-reconciliation:1",
              "IB positions differ: account 1",
              "Recorded positions of account 1 differ from the Open Positions IB reports at the end " +
                "of 2026-11-06:\nVOD (contract 12345): recorded 10, IB reports 8"
            )
          )
        }

        "name an instrument only IB reports as recorded at zero" >> {
          for {
            f <- fixture()
            _ <- f.reconcile(reported(position("888", "NEW", 3)))
            raised <- f.raised
          } yield lines(raised) must contain("NEW (contract 888): recorded 0, IB reports 3")
        }

        "name an instrument IB does not report by the symbol of its stored record" >> {
          for {
            f <- fixture(List(moved("777", 5)), List(stored("777", "XYZ")))
            _ <- f.reconcile(reported())
            raised <- f.raised
          } yield lines(raised) must contain("XYZ (contract 777): recorded 5, IB reports 0")
        }

        "name an instrument IB does not report and has no stored record by its contract alone" >> {
          for {
            f <- fixture(List(moved("777", 5)))
            _ <- f.reconcile(reported())
            raised <- f.raised
          } yield lines(raised) must contain("contract 777: recorded 5, IB reports 0")
        }

        "print quantities without trailing zeros" >> {
          for {
            f <- fixture(List(moved("12345", BigDecimal("12.500000000000000000"))))
            _ <- f.reconcile(reported(position("12345", "VOD", 12)))
            raised <- f.raised
          } yield lines(raised) must contain("VOD (contract 12345): recorded 12.5, IB reports 12")
        }

        "are not raised while the recorded and reported positions match" >> {
          for {
            f <- fixture(List(moved("12345", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 10)))
            raised <- f.raised
          } yield raised must beEmpty
        }

        "are raised once while they stay the same" >> {
          for {
            f <- fixture(List(moved("12345", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 8)))
            _ <- f.reconcile(reported(position("12345", "VOD", 8)))
            raised <- f.raised
          } yield raised.map(_.name) must_== List("ibkr-reconciliation:1")
        }

        "are raised again when they change" >> {
          for {
            f <- fixture(List(moved("12345", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 8)))
            _ <- f.reconcile(reported(position("12345", "VOD", 7)))
            raised <- f.raised
          } yield {
            raised.map(_.name) must_== List("ibkr-reconciliation:1", "ibkr-reconciliation:1")
            lines(raised).filter(_.startsWith("VOD")) must_== List(
              "VOD (contract 12345): recorded 10, IB reports 8",
              "VOD (contract 12345): recorded 10, IB reports 7"
            )
          }
        }

        "are raised again when they come back after clearing" >> {
          for {
            f <- fixture(List(moved("12345", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 8)))
            _ <- f.reconcile(reported(position("12345", "VOD", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 8)))
            raised <- f.raised
          } yield raised.map(_.name) must_== List(
            "ibkr-reconciliation:1",
            "ibkr-reconciled:1",
            "ibkr-reconciliation:1"
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
            reconciler <- reconcilerOver(List(moved("12345", 10)), Nil, flaky)
            first <- reconciler
              .reconcile(accountId, reported(position("12345", "VOD", 8)))
              .attempt
            _ <- reconciler.reconcile(accountId, reported(position("12345", "VOD", 8)))
            raised <- recorded.raised
          } yield {
            first must beLeft[Throwable]
            raised.map(_.name) must_== List("ibkr-reconciliation:1")
          }
        }

        "are kept apart for each account" >> {
          val operations = List(moved("12345", 10), moved("12345", 10, owner = otherAccountId))
          for {
            f <- fixture(operations)
            _ <- f.reconcile(reported(position("12345", "VOD", 8)), accountId)
            _ <- f.reconcile(reported(position("12345", "VOD", 8)), otherAccountId)
            raised <- f.raised
          } yield raised.map(_.name) must_== List("ibkr-reconciliation:1", "ibkr-reconciliation:2")
        }
      }

      "the all-clear" >> {
        "is raised as ibkr-reconciled:<account id> once the differences clear, and only once" >> {
          for {
            f <- fixture(List(moved("12345", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 8)))
            _ <- f.reconcile(reported(position("12345", "VOD", 10)))
            _ <- f.reconcile(reported(position("12345", "VOD", 10)))
            raised <- f.raised
          } yield {
            raised.map(_.name) must_== List("ibkr-reconciliation:1", "ibkr-reconciled:1")
            raised.last must_== RaisedSystemAlert(
              "ibkr-reconciled:1",
              "IB positions match: account 1",
              "Recorded positions of account 1 match the Open Positions IB reports at the end of " +
                "2026-11-06."
            )
          }
        }
      }
    }
  }
}
