package su.wps.trading.pillar.storages

import su.wps.trading.pillar.models.domain.{AccountId, TcsPortfolioOperation}

import java.time.ZonedDateTime

trait TcsPortfolioOperationStorage[F[_]] {
  def lastOperation(accountId: AccountId): F[Option[TcsPortfolioOperation]]

  def operationsByDtRange(
    accountId: AccountId,
    since: ZonedDateTime,
    until: ZonedDateTime
  ): F[Set[TcsPortfolioOperation]]

  def saveOperations(operations: List[TcsPortfolioOperation]): F[Unit]
}
