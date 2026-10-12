package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.gateways.MarketDataSources
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.paper.{PaperOrder, PaperPosition}
import st.btu.trading.pillar.storages.PaperStorage

// Single-account app for now, same as PositionsHandler/PortfolioHandler: accountId is a
// constructor argument rather than derived from the chat. Prices positions with the latest quote
// from the ticker's own routed source (MarketDataSources.forTicker), same as PositionsHandler.
final class PaperHandler[F[_]: Monad](
  tgGateway: TgGateway[F],
  paper: PaperStorage[F],
  marketDataSources: MarketDataSources[F],
  accountId: AccountId
) extends CommandHandler[F] {

  private val RecentOrdersLimit = 5

  def command: Command = Command.Paper

  def usage: String = "/paper - virtual paper-trading account"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    paper.account(accountId).flatMap {
      case None => Replies.reply(tgGateway, chatId, "No paper account configured.")
      case Some(account) =>
        for {
          positions <- paper.positions(accountId)
          priced <- positions.traverse(withLatestPrice)
          orders <- paper.recentOrders(accountId, RecentOrdersLimit)
          _ <- Replies.reply(tgGateway, chatId, format(account.cash, priced, orders))
        } yield ()
    }

  private def withLatestPrice(position: PaperPosition): F[(PaperPosition, Option[BigDecimal])] =
    marketDataSources
      .forTicker(position.ticker)
      .flatMap(_.latestQuote(position.ticker))
      .map(quote => position -> quote.map(_.price))

  // Equity sums cash with only the priced positions' value - an unpriced one (no quote available
  // right now) is shown on its own line instead, same spirit as PositionsHandler's unpriced marker.
  private def format(
    cash: BigDecimal,
    positions: List[(PaperPosition, Option[BigDecimal])],
    orders: List[PaperOrder]
  ): String = {
    val positionsSection =
      if (positions.isEmpty) "No open positions." else positions.map(formatPosition).mkString("\n")
    val pricedValue = positions.collect { case (p, Some(price)) => BigDecimal(p.quantity) * price }
    val equity = cash + pricedValue.sum
    val ordersSection =
      if (orders.isEmpty) "No orders yet." else orders.map(formatOrder).mkString("\n")
    List(
      s"Cash: ${Replies.scaled(cash)}",
      positionsSection,
      s"Equity: ${Replies.scaled(equity)}",
      "",
      "Recent orders:",
      ordersSection
    ).mkString("\n")
  }

  private def formatPosition(positionWithPrice: (PaperPosition, Option[BigDecimal])): String =
    positionWithPrice match {
      case (position, Some(price)) =>
        val value = BigDecimal(position.quantity) * price
        val pnl = (price - position.averageCost) * BigDecimal(position.quantity)
        s"${position.ticker.value} ${position.quantity} @ ${Replies.scaled(price)} (avg ${Replies
            .scaled(position.averageCost)}) = ${Replies.scaled(value)}, P&L ${Replies.scaled(pnl)}"
      case (position, None) =>
        s"${position.ticker.value} ${position.quantity} (avg ${Replies.scaled(position.averageCost)}, unpriced)"
    }

  private def formatOrder(order: PaperOrder): String = {
    val price = order.fillPrice.fold("-")(Replies.scaled)
    val reason = order.reason.getOrElse("-")
    s"${order.status.value} ${order.side.name} ${order.quantity} @ $price ($reason)"
  }
}
