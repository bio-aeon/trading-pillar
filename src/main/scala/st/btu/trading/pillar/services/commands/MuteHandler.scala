package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.alerts.AlertDispatcher
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

import scala.util.Try

final class MuteHandler[F[_]: Monad: Clock](tgGateway: TgGateway[F], dispatcher: AlertDispatcher[F])
    extends CommandHandler[F] {

  private val DefaultMinutes = 60

  def command: Command = Command.Mute

  def usage: String = "/mute (minutes, default 60) - pause alert delivery"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] = {
    val minutes =
      args.headOption.flatMap(s => Try(s.toInt).toOption).filter(_ > 0).getOrElse(DefaultMinutes)
    for {
      now <- Clock[F].realTimeInstant
      _ <- dispatcher.mute(now.plusSeconds(minutes.toLong * 60))
      _ <- Replies.reply(tgGateway, chatId, s"Alerts muted for $minutes minute(s).")
    } yield ()
  }
}
