package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.PortfolioValuationStorage

// Single-account app for now, same as WatchlistHandler/DividendsHandler: accountId is a
// constructor argument rather than derived from the chat.
final class PortfolioHandler[F[_]: Monad](
  tgGateway: TgGateway[F],
  valuations: PortfolioValuationStorage[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Portfolio

  def usage: String = "/portfolio - latest portfolio valuation"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    valuations.latest(accountId).flatMap {
      case None => Replies.reply(tgGateway, chatId, "No valuation history yet.")
      case Some(point) =>
        seriesLength(point).flatMap(n => Replies.reply(tgGateway, chatId, format(point, n)))
    }

  // "Series length" as of the latest point: how many days of history back it up. There is no
  // dedicated count method on PortfolioValuationStorage, so this reads the series over the same
  // lookback window PerformanceHandler.Period.All uses, no real account history predates.
  private def seriesLength(latest: ValuationPoint): F[Int] =
    valuations
      .series(
        accountId,
        latest.date.minusYears(PerformanceHandler.InceptionLookbackYears.toLong),
        latest.date
      )
      .map(_.size)

  private def format(point: ValuationPoint, seriesLength: Int): String = {
    val profit = point.value - point.invested
    List(
      s"Date: ${point.date}",
      s"Value: ${Replies.scaled(point.value)}",
      s"Invested: ${Replies.scaled(point.invested)}",
      s"Profit: ${Replies.scaled(profit)}",
      s"History: $seriesLength day(s)"
    ).mkString("\n")
  }
}
