package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.paper.{PaperAccount, PaperOrder, PaperOrderId, PaperPosition}

/** Virtual paper-trading accounts, positions and orders. */
trait PaperStorage[F[_]] {

  /** `accountId`'s paper account, or `None` if one hasn't been provisioned. */
  def account(accountId: AccountId): F[Option[PaperAccount]]

  /** Sets `accountId`'s cash balance. */
  def updateCash(accountId: AccountId, cash: BigDecimal): F[Unit]

  /** Records a placed order. */
  def insertOrder(order: PaperOrder): F[PaperOrderId]

  /** `accountId`'s current paper positions. */
  def positions(accountId: AccountId): F[List[PaperPosition]]

  /** Inserts or updates `position`. */
  def upsertPosition(position: PaperPosition): F[Unit]

  /** Removes `ticker`'s position for `accountId`, once it closes to zero. */
  def deletePosition(accountId: AccountId, ticker: Ticker): F[Unit]

  /** The most recent orders for `accountId`, newest first. */
  def recentOrders(accountId: AccountId, limit: Int): F[List[PaperOrder]]
}
