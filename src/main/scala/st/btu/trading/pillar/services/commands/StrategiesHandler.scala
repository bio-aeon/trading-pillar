package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.strategy.{Strategy, StrategyRegistry}

final class StrategiesHandler[F[_]: Monad](tgGateway: TgGateway[F], registry: StrategyRegistry[F])
    extends CommandHandler[F] {
  def command: Command = Command.Strategies

  def usage: String = "/strategies - list registered strategies"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    registry.list.flatMap(strategies => Replies.reply(tgGateway, chatId, format(strategies)))

  private def format(strategies: List[Strategy]): String =
    if (strategies.isEmpty) "No strategies registered."
    else strategies.map(formatStrategy).mkString("\n")

  private def formatStrategy(s: Strategy): String = {
    val params =
      if (s.parameters.isEmpty) ""
      else s" (${s.parameters.map { case (k, v) => s"$k=$v" }.mkString(", ")})"
    s"${s.id.value} - ${s.name}$params"
  }
}
