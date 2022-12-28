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

trait CommandHandleService[F[_]] {
  def handleCommand(cmd: Command): F[Unit]
}

object CommandHandleService {

  def create[I[_]: Functor, F[_]: Monad: Clock](
    privateChatId: tg.ChatId,
    tgGateway: TgGateway[F],
    tcsFacade: TcsFacade[F]
  )(implicit logs: Logs[I, F]): I[CommandHandleService[F]] =
    logs
      .forService[CommandHandleService[F]]
      .map(implicit log => new Impl[F](privateChatId, tgGateway, tcsFacade))

  final private class Impl[F[_]: Monad: Clock](
    privateChatId: tg.ChatId,
    tgGateway: TgGateway[F],
    tcsFacade: TcsFacade[F]
  ) extends CommandHandleService[F] {
    def handleCommand(cmd: Command): F[Unit] =
      cmd match {
        case Command.Ping => handlePing
        case Command.Info => handleInfo
        case Command.Unknown => handleUnknown
      }

    private def handlePing: F[Unit] =
      for {
        now <- Clock[F].realTime.map(_.toMillis).map(Instant.ofEpochMilli)
        zoneId = ZoneId.systemDefault
        nowStr = ZonedDateTime
          .ofInstant(now, zoneId)
          .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        _ <- tgGateway.sendMessage(privateChatId, s"Working normally. Time is: $nowStr.")
      } yield ()

    private def handleInfo: F[Unit] =
      tcsFacade.portfolio.map(
        _.positions.map(x => s"${x.name} - ${x.balance.toInt}").mkString("\n")
      ) >>= { msg =>
        tgGateway.sendMessage(privateChatId, msg)
      }

    private def handleUnknown: F[Unit] =
      tgGateway.sendMessage(privateChatId, s"Unknown command.")
  }
}
