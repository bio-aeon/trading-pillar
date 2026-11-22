package st.btu.trading.pillar.services

import cats.Functor
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.OperationHistoryYears
import st.btu.trading.pillar.analytics.portfolio.Holdings
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.storages.AccountOperationStorage

import java.time.{Instant, ZoneOffset}

// What an account held at an instant, from the operations a storage recorded for it.
private[services] object AccountHoldings {

  /** The operations of `accountId` that occurred before `until`, read as far back as the valuation
    * and the tax services read an account's operations.
    */
  def operationsBefore[F[_]](
    operations: AccountOperationStorage[F],
    accountId: AccountId,
    until: Instant
  ): F[Vector[AccountOperation]] = {
    val since = until.atZone(ZoneOffset.UTC).minusYears(OperationHistoryYears.toLong).toInstant
    operations.operations(accountId, since, until)
  }

  /** The `Holdings` of the executed operations of `accountId` that occurred before `until`, read as
    * far back as the valuation and the tax services read an account's operations.
    */
  def before[F[_]: Functor](
    operations: AccountOperationStorage[F],
    accountId: AccountId,
    until: Instant
  ): F[Map[InstrumentKey, BigDecimal]] =
    operationsBefore(operations, accountId, until).map(Holdings.of)

  /** The cash that the executed `operations` leave in each currency: the sum of their `amount`. */
  def cashOf(operations: Iterable[AccountOperation]): Map[Currency, BigDecimal] =
    operations
      .filter(_.state == OperationState.Executed)
      .groupMapReduce(_.currency)(_.amount)(_ + _)
}
