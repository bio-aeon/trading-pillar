package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.functor.*
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.paper.{PaperAccount, PaperOrder, PaperOrderId, PaperPosition}
import st.btu.trading.pillar.storages.PaperStorage

object PaperFakes {

  trait FakePaperStorage extends PaperStorage[IO] {
    def accounts: IO[Map[AccountId, PaperAccount]]
    def allPositions: IO[List[PaperPosition]]
    def allOrders: IO[List[PaperOrder]]
  }

  def paperStorage(
    accounts: List[PaperAccount] = List.empty,
    positions: List[PaperPosition] = List.empty,
    orders: List[PaperOrder] = List.empty
  ): IO[FakePaperStorage] =
    for {
      accountsRef <- Ref.of[IO, Map[AccountId, PaperAccount]](
        accounts.map(a => a.accountId -> a).toMap
      )
      positionsRef <- Ref.of[IO, List[PaperPosition]](positions)
      ordersRef <- Ref.of[IO, List[PaperOrder]](orders)
      idRef <- Ref.of[IO, Long](orders.flatMap(_.id.map(_.value)).maxOption.getOrElse(0L))
    } yield new FakePaperStorage {
      def account(accountId: AccountId): IO[Option[PaperAccount]] =
        accountsRef.get.map(_.get(accountId))

      def insertOrder(order: PaperOrder): IO[PaperOrderId] =
        for {
          nextId <- idRef.updateAndGet(_ + 1)
          id = PaperOrderId(nextId)
          _ <- ordersRef.update(_ :+ order.copy(id = Some(id)))
        } yield id

      def recordFill(
        order: PaperOrder,
        cash: BigDecimal,
        position: Option[PaperPosition]
      ): IO[PaperOrderId] =
        for {
          _ <- accountsRef.update(_.updatedWith(order.accountId)(_.map(_.copy(cash = cash))))
          _ <- positionsRef.update { current =>
            val held =
              (p: PaperPosition) => p.accountId == order.accountId && p.ticker == order.ticker
            position match {
              case Some(updated) =>
                val idx = current.indexWhere(held)
                if (idx >= 0) current.updated(idx, updated) else current :+ updated
              case None => current.filterNot(held)
            }
          }
          id <- insertOrder(order)
        } yield id

      def positions(accountId: AccountId): IO[List[PaperPosition]] =
        positionsRef.get.map(_.filter(_.accountId == accountId))

      def recentOrders(accountId: AccountId, limit: Int): IO[List[PaperOrder]] =
        ordersRef.get.map(_.filter(_.accountId == accountId).takeRight(limit).reverse)

      def accounts: IO[Map[AccountId, PaperAccount]] = accountsRef.get

      def allPositions: IO[List[PaperPosition]] = positionsRef.get

      def allOrders: IO[List[PaperOrder]] = ordersRef.get
    }
}
