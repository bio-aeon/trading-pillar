package st.btu.trading.pillar.analytics.portfolio

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.testkit.AnalyticsFakes.accountOperation
import st.btu.trading.pillar.testkit.MarketDataFakes.instrumentKey

import java.time.Instant

class HoldingsSpec extends Specification {

  private val accountId = AccountId(1)
  private val at = Instant.parse("2026-01-05T10:00:00Z")

  private val f1 = instrumentKey("F1")
  private val f2 = instrumentKey("F2")

  // `quantity` units of the instrument bought, or sold when it is negative; the cash is of no
  // concern to a position.
  private def moved(
    instrument: String,
    quantity: BigDecimal,
    state: OperationState = OperationState.Executed
  ): AccountOperation = {
    val kind = if (quantity.signum > 0) OperationKind.Buy else OperationKind.Sell
    accountOperation(accountId, kind, 0, at, Some(instrument), quantity, state = state)
  }

  "Holdings" >> {
    "of" >> {
      "sums the signed quantities of the executed operations of each instrument" >> {
        Holdings.of(List(moved("F1", 10), moved("F1", -4), moved("F2", 3))) must_==
          Map(f1 -> BigDecimal(6), f2 -> BigDecimal(3))
      }

      "leaves out an instrument whose position is closed" >> {
        Holdings.of(List(moved("F1", 5), moved("F1", -5), moved("F2", 1))) must_==
          Map(f2 -> BigDecimal(1))
      }

      "keeps a short position, as a negative quantity" >> {
        Holdings.of(List(moved("F1", -3))) must_== Map(f1 -> BigDecimal(-3))
      }

      "ignores operations that are not executed" >> {
        val operations = List(
          moved("F1", 5),
          moved("F1", 3, OperationState.Pending),
          moved("F1", 2, OperationState.Canceled)
        )

        Holdings.of(operations) must_== Map(f1 -> BigDecimal(5))
      }

      "ignores operations that name no instrument" >> {
        val deposit = accountOperation(accountId, OperationKind.Deposit, 1000, at)

        Holdings.of(List(deposit)) must beEmpty
      }

      "lists no instrument that only fees and dividends concern" >> {
        val fee = accountOperation(accountId, OperationKind.Fee, -1, at, Some("F1"))
        val dividend = accountOperation(accountId, OperationKind.Dividend, 2, at, Some("F1"))

        Holdings.of(List(fee, dividend)) must beEmpty
      }
    }

    "after" >> {
      "applies the operations on top of the positions already held" >> {
        val held = Holdings.of(List(moved("F1", 5)))

        Holdings.after(held, List(moved("F1", -5), moved("F2", 2))) must_==
          Map(f2 -> BigDecimal(2))
      }
    }
  }
}
