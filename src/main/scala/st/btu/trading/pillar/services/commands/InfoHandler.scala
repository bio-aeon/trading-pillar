package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.facades.TcsFacade
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

final class InfoHandler[F[_]: Monad](tgGateway: TgGateway[F], tcsFacade: TcsFacade[F])
    extends CommandHandler[F] {
  def command: Command = Command.Info

  def usage: String = "/info - portfolio positions"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    tcsFacade.portfolio
      .map(_.positions.map(x => s"${x.name} - ${x.balance.toInt}").mkString("\n"))
      .flatMap(msg => Replies.reply(tgGateway, chatId, msg))
}
