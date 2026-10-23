package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.{PortfolioValuationService, Rebalancer, Suggestion}
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.AllocationTargetStorage
import st.btu.trading.pillar.strategy.Side

import scala.util.Try

// Single-account app for now, same as DriftHandler/AllocationHandler: accountId is a constructor
// argument rather than derived from the chat; uses today's date (UTC) as the snapshot date.
final class RebalanceHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  valuations: PortfolioValuationService[F],
  targets: AllocationTargetStorage[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Rebalance

  def usage: String = "/rebalance (cash=<amount>) - rebalancing suggestions"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    parseCash(args) match {
      case Right(newCash) =>
        for {
          today <- Replies.todayUtc[F]
          accountTargets <- targets.byAccount(accountId)
          snapshot <- valuations.positionsAsOf(accountId, today)
          suggestions = Rebalancer.suggest(snapshot, accountTargets, newCash)
          _ <- Replies.reply(tgGateway, chatId, format(suggestions))
        } yield ()
      case Left(_) => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }

  // No args: rebalance the current total (sells and buys). "cash=<amount>": cash-only rebalance of
  // the given deposit (buys only). Any other token is a usage error.
  private def parseCash(args: List[String]): Either[String, Option[BigDecimal]] =
    args match {
      case Nil => Right(None)
      case token :: _ if token.toLowerCase.startsWith("cash=") =>
        Try(BigDecimal(token.drop("cash=".length))).toOption match {
          case Some(amount) => Right(Some(amount))
          case None => Left(s"invalid cash amount: $token")
        }
      case token :: _ => Left(s"unknown argument: $token")
    }

  private def format(suggestions: List[Suggestion]): String =
    if (suggestions.isEmpty) "Already within thresholds."
    else suggestions.map(formatSuggestion).mkString("\n")

  private def formatSuggestion(s: Suggestion): String = {
    val verb = s.side match {
      case Side.Buy => "Buy"
      case Side.Sell => "Sell"
    }
    s"$verb ${s.dimension.value}/${s.key}: ${Replies.scaled(s.amount)}"
  }
}
