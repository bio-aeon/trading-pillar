package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.{Period, PortfolioMetrics, ValuationPoint}
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.PortfolioValuationStorage

// Single-account app for now, same as WatchlistHandler/DividendsHandler: accountId is a
// constructor argument rather than derived from the chat.
final class PerformanceHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  valuations: PortfolioValuationStorage[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Performance

  def usage: String = "/performance (1m|3m|6m|1y|all, default 1y) - portfolio performance"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] = {
    val period = args.headOption.flatMap(Period.parse).getOrElse(Period.OneYear)
    for {
      today <- Replies.todayUtc[F]
      points <- valuations.series(accountId, period.from(today), today)
      _ <- Replies.reply(tgGateway, chatId, format(points))
    } yield ()
  }

  private def format(points: Vector[ValuationPoint]): String =
    PortfolioMetrics.of(points).fold("No valuation history yet.")(report)

  private def report(metrics: PortfolioMetrics): String =
    List(
      s"Period: ${metrics.firstDate} to ${metrics.lastDate}",
      s"Total return: ${Replies.pct(metrics.totalReturn)}",
      s"TWR: ${Replies.pct(metrics.timeWeightedReturn)}",
      s"MWR: ${metrics.moneyWeightedReturn.fold("n/a")(Replies.pct)}",
      s"Volatility: ${Replies.pct(metrics.volatility)}",
      s"Sharpe: ${Replies.scaled(metrics.sharpe)}",
      s"Max drawdown: ${Replies.pct(metrics.maxDrawdown)}"
    ).mkString("\n")
}
