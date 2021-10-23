package su.wps.trading.pillar.processes

import cats.effect.{Clock, Timer}
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Functor, Monad}
import fs2.Stream
import su.wps.trading.pillar.gateways.TgGateway
import su.wps.trading.pillar.models.domain.Command
import su.wps.trading.pillar.models.tg
import su.wps.trading.pillar.services.CommandHandleService
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._

import scala.concurrent.duration._

trait TgUpdatesProcess[F[_]] {
  def run: Stream[F, Unit]
}

object TgUpdatesProcess {
  def create[I[_]: Functor, F[_]: Monad: Timer](
    tgGateway: TgGateway[F],
    commandHandleService: CommandHandleService[F]
  )(implicit logs: Logs[I, F]): I[TgUpdatesProcess[F]] =
    logs
      .forService[TgUpdatesProcess[F]]
      .map(implicit log => new Impl[F](tgGateway, commandHandleService))

  final private class Impl[F[_]: Logging: Timer](
    tgGateway: TgGateway[F],
    commandHandleService: CommandHandleService[F]
  )(implicit F: Monad[F], clock: Clock[F])
      extends TgUpdatesProcess[F] {
    def run: Stream[F, Unit] =
      processUpdatesLoop(0)

    private def processUpdatesLoop(offset: Long): Stream[F, Unit] =
      Stream
        .eval(tgGateway.getUpdates(offset))
        .evalTap { updates =>
          F.whenA(updates.result.nonEmpty) {
            info"Received tg updates: ${updates.toString}" *>
              updates.result
                .flatMap(x => x.message.flatMap(m => m.text.map(m.chat.id -> _)))
                .traverse((processUpdate _).tupled)
                .void
          }
        }
        .flatMap { updates =>
          Stream.sleep(200.milliseconds) >> processUpdatesLoop(
            lastOffset(updates).getOrElse(offset)
          )
        }
        .handleErrorWith { e =>
          Stream.eval(errorCause"Tg updates process loop failure" (e)) >>
            Stream.sleep(10.seconds) >> processUpdatesLoop(offset)
        }

    private def lastOffset(response: tg.Result[List[tg.Update]]): Option[Long] =
      response.result match {
        case Nil => None
        case nonEmpty => Some(nonEmpty.maxBy(_.update_id).update_id)
      }

    private def processUpdate(chatId: tg.ChatId, msg: String): F[Unit] = {
      val cmd = Command.withValueOpt(msg.drop(1)).getOrElse(Command.Unknown)
      commandHandleService.handleCommand(cmd)
    }
  }
}
