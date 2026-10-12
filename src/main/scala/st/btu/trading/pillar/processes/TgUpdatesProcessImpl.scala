package st.btu.trading.pillar.processes

import cats.Functor
import cats.effect.kernel.Ref
import cats.effect.{Async, Resource, Sync, Temporal}
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import fs2.Stream
import sttp.client3.SttpBackend
import st.btu.trading.pillar.facades.TcsFacadeImpl
import st.btu.trading.pillar.gateways.{TgGateway, TgGatewayImpl}
import st.btu.trading.pillar.models.{tcs, tg}
import st.btu.trading.pillar.services.commands.*
import st.btu.trading.pillar.services.{CommandHandleService, CommandHandleServiceImpl}
import tofu.kernel.types.Throws
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import scala.concurrent.duration.*

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
            updates.result.traverse(processOneUpdate) >> lastOffset(updates)
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

  // A plain message is routed by its own chat/text. A callback_query has no message/text of its
  // own - when its data looks like a command, the callback is answered first (so the tapped
  // button's loading state clears even if the command itself is slow or fails), then the data is
  // routed as command text for the chat the keyboard was attached to. A callback with no message
  // (deleted/expired) or with data that isn't a command is otherwise ignored.
  private def processOneUpdate(update: tg.Update): F[Unit] =
    update.message.flatMap(m => m.text.map(m.chat.id -> _)) match {
      case Some((chatId, text)) => processUpdate(chatId, text)
      case None => update.callback_query.fold(F.unit)(processCallback)
    }

  private def processCallback(callbackQuery: tg.CallbackQuery): F[Unit] =
    callbackQuery.data.filter(CommandParser.isCommand) match {
      case Some(text) =>
        tgGateway.answerCallbackQuery(callbackQuery.id) >>
          callbackQuery.message.traverse(m => processUpdate(m.chat.id, text)).void
      case None => F.unit
    }

  private def processUpdate(chatId: tg.ChatId, msg: String): F[Unit] =
    CommandParser.parse(msg) match {
      case Some(parsed) => commandHandleService.handleCommand(chatId, parsed)
      case None => F.unit
    }
}

object TgUpdatesProcessImpl {

  /** Wires the Telegram updates process: a gateway for `tgToken`, the built-in ping/info/help
    * handlers plus `extraHandlers`, and the command router, holding the Tinkoff gRPC channel (for
    * `/info`) open for the resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async: Throws: Lift[*[_], I]](
    tgToken: tg.Token,
    brokerAccountId: tcs.BrokerAccountId,
    tcsToken: tcs.Token,
    backend: SttpBackend[F, Any],
    extraHandlers: List[CommandHandler[F]] = Nil
  )(implicit logs: Logs[I, F]): Resource[I, TgUpdatesProcessImpl[F]] =
    for {
      tgGateway <- Resource.eval(TgGatewayImpl.create[I, F](tgToken, backend))
      tcsFacade <- TcsFacadeImpl.resource[I, F](brokerAccountId, tcsToken)
      pingHandler = new PingHandler[F](tgGateway)
      infoHandler = new InfoHandler[F](tgGateway, tcsFacade)
      baseHandlers = List[CommandHandler[F]](pingHandler, infoHandler) ++ extraHandlers
      helpHandler = new HelpHandler[F](tgGateway, baseHandlers.map(_.usage) :+ HelpHandler.Usage)
      commandHandleService <- Resource.eval(
        CommandHandleServiceImpl.create[I, F](tgGateway, baseHandlers :+ helpHandler)
      )
      tgUpdatesProcess <- Resource.eval(
        TgUpdatesProcessImpl.create[I, F](tgGateway, commandHandleService)
      )
    } yield tgUpdatesProcess

  /** Wires the Telegram updates process backed by `tgGateway` and `commandHandleService`. */
  def create[I[_]: Functor, F[_]: Temporal](
    tgGateway: TgGateway[F],
    commandHandleService: CommandHandleService[F]
  )(implicit logs: Logs[I, F]): I[TgUpdatesProcessImpl[F]] =
    logs
      .forService[TgUpdatesProcess[F]]
      .map(implicit log => new TgUpdatesProcessImpl[F](tgGateway, commandHandleService))
}
