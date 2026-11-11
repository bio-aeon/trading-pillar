package st.btu.trading.pillar.services

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{
  AccountId,
  AccountOperation,
  InstrumentKey,
  OperationKind
}
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
  }
}
