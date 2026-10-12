package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.functor.*
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker
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

      def updateCash(accountId: AccountId, cash: BigDecimal): IO[Unit] =
        accountsRef.update(_.updatedWith(accountId)(_.map(_.copy(cash = cash))))

      def insertOrder(order: PaperOrder): IO[PaperOrderId] =
        for {
          nextId <- idRef.updateAndGet(_ + 1)
          id = PaperOrderId(nextId)
          _ <- ordersRef.update(_ :+ order.copy(id = Some(id)))
        } yield id

      def positions(accountId: AccountId): IO[List[PaperPosition]] =
        positionsRef.get.map(_.filter(_.accountId == accountId))

      def upsertPosition(position: PaperPosition): IO[Unit] =
        positionsRef.update { current =>
          val idx =
            current.indexWhere(p =>
              p.accountId == position.accountId && p.ticker == position.ticker
            )
          if (idx >= 0) current.updated(idx, position) else current :+ position
        }

      def deletePosition(accountId: AccountId, ticker: Ticker): IO[Unit] =
        positionsRef.update(_.filterNot(p => p.accountId == accountId && p.ticker == ticker))

      def recentOrders(accountId: AccountId, limit: Int): IO[List[PaperOrder]] =
        ordersRef.get.map(_.filter(_.accountId == accountId).takeRight(limit).reverse)

      def accounts: IO[Map[AccountId, PaperAccount]] = accountsRef.get

      def allPositions: IO[List[PaperPosition]] = positionsRef.get

      def allOrders: IO[List[PaperOrder]] = ordersRef.get
    }
}
