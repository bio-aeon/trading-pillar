package su.wps.trading.pillar.services

import cats.effect.Clock
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Functor, Monad}
import su.wps.trading.pillar.facades.TcsFacade
import su.wps.trading.pillar.gateways.TgGateway
import su.wps.trading.pillar.models.domain.Command
import su.wps.trading.pillar.models.tg
import tofu.logging.Logs

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZonedDateTime}

final class CommandHandleServiceImpl[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  tcsFacade: TcsFacade[F]
) extends CommandHandleService[F] {
  def handleCommand(chatId: tg.ChatId, cmd: Command): F[Unit] =
    cmd match {
      case Command.Ping => handlePing(chatId)
      case Command.Info => handleInfo(chatId)
      case Command.Unknown => handleUnknown(chatId)
    }

  private def handlePing(chatId: tg.ChatId): F[Unit] =
    for {
      now <- Clock[F].realTime.map(_.toMillis).map(Instant.ofEpochMilli)
      zoneId = ZoneId.systemDefault
      nowStr = ZonedDateTime
        .ofInstant(now, zoneId)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
      _ <- tgGateway.sendMessage(chatId, s"Working normally. Time is: $nowStr.")
    } yield ()

  private def handleInfo(chatId: tg.ChatId): F[Unit] =
    tcsFacade.portfolio.map(_.positions.map(x => s"${x.name} - ${x.balance.toInt}").mkString("\n")) >>= {
      msg =>
        tgGateway.sendMessage(chatId, msg)
    }

  private def handleUnknown(chatId: tg.ChatId): F[Unit] =
    tgGateway.sendMessage(chatId, s"Unknown command.")
}

object CommandHandleServiceImpl {

  def create[I[_]: Functor, F[_]: Monad: Clock](tgGateway: TgGateway[F], tcsFacade: TcsFacade[F])(
    implicit logs: Logs[I, F]
  ): I[CommandHandleServiceImpl[F]] =
    logs
      .forService[CommandHandleService[F]]
      .map(implicit log => new CommandHandleServiceImpl[F](tgGateway, tcsFacade))
}
