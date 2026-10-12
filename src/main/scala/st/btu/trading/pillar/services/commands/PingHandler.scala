package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

import java.time.{Instant, ZoneId, ZonedDateTime}

final class PingHandler[F[_]: Monad: Clock](tgGateway: TgGateway[F]) extends CommandHandler[F] {
  def command: Command = Command.Ping

  def usage: String = "/ping - health check"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    for {
      now <- Clock[F].realTime.map(_.toMillis).map(Instant.ofEpochMilli)
      zoneId = ZoneId.systemDefault
      nowStr = ZonedDateTime.ofInstant(now, zoneId).format(Replies.TimeFormatter)
      _ <- Replies.reply(tgGateway, chatId, s"Working normally. Time is: $nowStr.")
    } yield ()
}
