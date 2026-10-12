package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.{
  PortfolioValuationService,
  PositionSnapshot,
  PositionValue
}
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg

// Single-account app for now, same as PerformanceHandler/PortfolioHandler: accountId is a
// constructor argument rather than derived from the chat. Uses today's date (UTC, same convention
// WatchlistHandler/PerformanceHandler use for "now") as the snapshot date.
final class PositionsHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  valuations: PortfolioValuationService[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Positions

  def usage: String = "/positions - current positions by value"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    for {
      today <- Replies.todayUtc[F]
      snapshot <- valuations.positionsAsOf(accountId, today)
      _ <- Replies.reply(tgGateway, chatId, format(snapshot))
    } yield ()

  // Priced positions first, largest value first; unpriced ones (nothing to sort by) last, ordered
  // by their own label for a stable reply.
  private def format(snapshot: PositionSnapshot): String = {
    val (priced, unpriced) = snapshot.positions.partition(_.value.isDefined)
    val lines = priced.sortBy(p => -p.value.get).map(formatPosition) ++
      unpriced.sortBy(label).map(formatPosition)
    val positionsText = if (lines.isEmpty) "No positions." else lines.mkString("\n")
    s"$positionsText\nCash: ${Replies.scaled(snapshot.cash)}\nTotal: ${Replies.scaled(snapshot.total)}"
  }

  private def formatPosition(p: PositionValue): String =
    p.value match {
      case Some(value) =>
        val price = p.price.fold("?")(Replies.scaled)
        val weight = p.weight.fold("?")(w => Replies.pct(w))
        s"${label(p)} ${p.quantity} @ $price = ${Replies.scaled(value)} ($weight)"
      case None =>
        s"${label(p)} ${p.quantity} (unpriced)"
    }

  private def label(p: PositionValue): String = p.ticker.fold(p.figi.value)(_.value)
}
