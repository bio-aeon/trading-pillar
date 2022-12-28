package su.wps.trading.pillar.storages

import su.wps.trading.pillar.models.domain.TcsPortfolioOperation

import java.time.ZonedDateTime

trait TcsPortfolioOperationStorage[F[_]] {
  def lastOperation: F[Option[TcsPortfolioOperation]]

  def operationsByDtRange(since: ZonedDateTime, until: ZonedDateTime): F[Set[TcsPortfolioOperation]]

  def saveOperations(operations: List[TcsPortfolioOperation]): F[Unit]
}
