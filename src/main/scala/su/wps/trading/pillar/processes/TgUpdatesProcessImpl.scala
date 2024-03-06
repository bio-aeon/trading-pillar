package su.wps.trading.pillar.processes

import cats.Functor
import cats.effect.kernel.Ref
import cats.effect.{Async, Resource, Sync, Temporal}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import fs2.Stream
import sttp.client3.SttpBackend
import su.wps.trading.pillar.facades.TcsFacadeImpl
import su.wps.trading.pillar.gateways.{TgGateway, TgGatewayImpl}
import su.wps.trading.pillar.models.domain.Command
import su.wps.trading.pillar.models.{tcs, tg}
import su.wps.trading.pillar.services.{CommandHandleService, CommandHandleServiceImpl}
import tofu.kernel.types.Throws
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._

import scala.concurrent.duration._

final class TgUpdatesProcessImpl[F[_]: Logging](
  tgGateway: TgGateway[F],
  commandHandleService: CommandHandleService[F]
)(implicit F: Temporal[F])
    extends TgUpdatesProcess[F] {
  def run: Stream[F, Unit] = Stream.eval(Ref[F].of(0L)).flatMap(processUpdatesLoop)

  private def processUpdatesLoop(offsetRef: Ref[F, Long]): Stream[F, Unit] =
    Stream
      .eval(offsetRef.get.flatMap(tgGateway.getUpdates))
      .evalTap { updates =>
        F.whenA(updates.result.nonEmpty) {
          info"Received tg updates: ${updates.toString}" *>
            updates.result
              .flatMap(x => x.message.flatMap(m => m.text.map(m.chat.id -> _)))
              .traverse((processUpdate _).tupled) >> lastOffset(updates)
            .traverse(offsetRef.set)
            .void
        }
      }
      .flatMap(_ => Stream.sleep(200.milliseconds))
      .repeat
      .handleErrorWith { e =>
        Stream.eval(errorCause"Tg updates process loop failure" (e)) >>
          Stream.sleep(10.seconds) >> processUpdatesLoop(offsetRef)
      }

  private def lastOffset(response: tg.Result[List[tg.Update]]): Option[Long] =
    response.result match {
      case Nil => None
      case nonEmpty => Some(nonEmpty.maxBy(_.update_id).update_id)
    }

  private def processUpdate(chatId: tg.ChatId, msg: String): F[Unit] = {
    val cmd = Command.withValueOpt(msg.drop(1)).getOrElse(Command.Unknown)
    commandHandleService.handleCommand(chatId, cmd)
  }
}

object TgUpdatesProcessImpl {

  def resource[I[_]: Sync, F[_]: Async: Throws: Lift[*[_], I]](
    tgToken: tg.Token,
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    backend: SttpBackend[F, Any]
  )(implicit logs: Logs[I, F]): Resource[I, TgUpdatesProcessImpl[F]] =
    for {
      tgGateway <- Resource.eval(TgGatewayImpl.create[I, F](tgToken, backend))
      tcsFacade <- TcsFacadeImpl.resource[I, F](brokerAccountId, tcsToken)
      commandHandleService <- Resource.eval(
        CommandHandleServiceImpl.create[I, F](tgGateway, tcsFacade)
      )
      tgUpdatesProcess <- Resource.eval(
        TgUpdatesProcessImpl.create[I, F](tgGateway, commandHandleService)
      )
    } yield tgUpdatesProcess

  def create[I[_]: Functor, F[_]: Temporal](
    tgGateway: TgGateway[F],
    commandHandleService: CommandHandleService[F]
  )(implicit logs: Logs[I, F]): I[TgUpdatesProcessImpl[F]] =
    logs
      .forService[TgUpdatesProcess[F]]
      .map(implicit log => new TgUpdatesProcessImpl[F](tgGateway, commandHandleService))
}
