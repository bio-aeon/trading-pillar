package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.{Performance, Returns, Risk, Valuation}
import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.PortfolioValuationStorage

import java.time.LocalDate

// Single-account app for now, same as WatchlistHandler/DividendsHandler: accountId is a
// constructor argument rather than derived from the chat.
final class PerformanceHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  valuations: PortfolioValuationStorage[F],
  accountId: AccountId
) extends CommandHandler[F] {

  import PerformanceHandler.*

  def command: Command = Command.Performance

  def usage: String = "/performance [1m|3m|6m|1y|all] (default 1y) - portfolio performance"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] = {
    val period = args.headOption.flatMap(Period.parse).getOrElse(Period.OneYear)
    for {
      today <- Replies.todayUtc[F]
      points <- valuations.series(accountId, period.from(today), today)
      _ <- Replies.reply(tgGateway, chatId, format(points))
    } yield ()
  }

  // Volatility/Sharpe/max drawdown run on the raw value series' simple returns, not TWR-adjusted
  // ones - the same convention strategy.backtest.Metrics uses for an equity curve (see
  // docs/analytics.md); TWR/MWR are the ones that account for cash flows exactly.
  private def format(points: Vector[ValuationPoint]): String =
    if (points.isEmpty) "No valuation history yet."
    else {
      val series = points.map(p => Valuation(p.date, p.value, p.cashFlow))
      val values = points.map(_.value)
      val returns = Returns.simple(values)
      val totalReturn = Performance.totalReturn(points.head.value, points.last.value)
      List(
        s"Period: ${points.head.date} to ${points.last.date}",
        s"Total return: ${Replies.pct(totalReturn)}",
        s"TWR: ${Replies.pct(Performance.timeWeighted(series))}",
        s"MWR: ${Performance.moneyWeighted(series).fold("n/a")(Replies.pct)}",
        s"Volatility: ${Replies.pct(Risk.volatility(returns))}",
        s"Sharpe: ${Replies.scaled(Risk.sharpe(returns))}",
        s"Max drawdown: ${Replies.pct(Risk.maxDrawdown(values))}"
      ).mkString("\n")
    }
}

object PerformanceHandler {

  // No "since inception" storage query exists; this many years is a safe, JDBC-friendly stand-in
  // for "all"/"the full series" that no real account history predates - shared with
  // PortfolioHandler's own series-length lookback.
  val InceptionLookbackYears = 50

  sealed abstract class Period(val from: LocalDate => LocalDate)

  object Period {
    case object OneMonth extends Period(_.minusMonths(1))
    case object ThreeMonths extends Period(_.minusMonths(3))
    case object SixMonths extends Period(_.minusMonths(6))
    case object OneYear extends Period(_.minusYears(1))
    case object All extends Period(_.minusYears(InceptionLookbackYears.toLong))

    def parse(s: String): Option[Period] = s.toLowerCase match {
      case "1m" => Some(OneMonth)
      case "3m" => Some(ThreeMonths)
      case "6m" => Some(SixMonths)
      case "1y" => Some(OneYear)
      case "all" => Some(All)
      case _ => None
    }
  }
}
