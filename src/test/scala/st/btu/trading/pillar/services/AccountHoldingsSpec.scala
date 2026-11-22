package st.btu.trading.pillar.services

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.testkit.AnalyticsFakes
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.MarketDataFakes.instrumentKey

import java.time.Instant

class AccountHoldingsSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val until = Instant.parse("2026-11-10T12:00:00Z")

  private def bought(at: Instant, quantity: BigDecimal): AccountOperation =
    accountOperation(accountId, OperationKind.Buy, 0, at, Some("F1"), quantity)

  private def holdingsBefore(
    operations: List[AccountOperation]
  ): IO[Map[InstrumentKey, BigDecimal]] =
    AnalyticsFakes
      .accountOperationStorage(operations)
      .flatMap(AccountHoldings.before(_, accountId, until))

  "AccountHoldings" >> {
    "before" >> {
      "counts an operation from many years back" >> {
        holdingsBefore(List(bought(Instant.parse("2012-03-01T10:00:00Z"), 10)))
          .map(_ must_== Map(instrumentKey("F1") -> BigDecimal(10)))
      }

      "leaves out the operations that occurred at the instant or after it" >> {
        val operations =
          List(bought(until.minusSeconds(1), 4), bought(until, 3), bought(until.plusSeconds(1), 2))

        holdingsBefore(operations).map(_ must_== Map(instrumentKey("F1") -> BigDecimal(4)))
      }
    }

    "cashOf" >> {
      def cash(
        kind: OperationKind,
        amount: BigDecimal,
        currency: String,
        state: OperationState = OperationState.Executed
      ): AccountOperation =
        accountOperation(accountId, kind, amount, until, currency = currency, state = state)

      "adds up the amount of every kind of operation, per currency" >> {
        val operations = List(
          cash(OperationKind.Deposit, 100, "USDT"),
          cash(OperationKind.Buy, -40, "USDT"),
          cash(OperationKind.Fee, -1, "USDT"),
          cash(OperationKind.Deposit, 50, "EUR")
        )

        AccountHoldings.cashOf(operations) must_==
          Map(Currency("USDT") -> BigDecimal(59), Currency("EUR") -> BigDecimal(50))
      }

      "leaves out the operations that are not executed" >> {
        val operations = List(
          cash(OperationKind.Deposit, 100, "USDT"),
          cash(OperationKind.Deposit, 7, "USDT", OperationState.Pending),
          cash(OperationKind.Deposit, 9, "USDT", OperationState.Canceled)
        )

        AccountHoldings.cashOf(operations) must_== Map(Currency("USDT") -> BigDecimal(100))
      }

      "gives nothing for no operation" >> {
        AccountHoldings.cashOf(Nil) must beEmpty
      }
    }
  }
}
