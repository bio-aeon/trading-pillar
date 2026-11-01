package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.paper.{PaperAccount, PaperOrder, PaperOrderId, PaperPosition}

/** Virtual paper-trading accounts, positions and orders. */
trait PaperStorage[F[_]] {

  /** `accountId`'s paper account, or `None` if one hasn't been provisioned. */
  def account(accountId: AccountId): F[Option[PaperAccount]]

  /** Records an order that changes no cash or position, such as a rejection. */
  def insertOrder(order: PaperOrder): F[PaperOrderId]

  /** Records the filled `order` together with what it changes, all of it or, on failure, none: the
    * order's account gets `cash` as its balance, and its position in the order's ticker becomes
    * `position`, or is removed when `position` is `None`.
    */
  def recordFill(
    order: PaperOrder,
    cash: BigDecimal,
    position: Option[PaperPosition]
  ): F[PaperOrderId]

  /** `accountId`'s current paper positions. */
  def positions(accountId: AccountId): F[List[PaperPosition]]

  /** The most recent orders for `accountId`, newest first. */
  def recentOrders(accountId: AccountId, limit: Int): F[List[PaperOrder]]
}
