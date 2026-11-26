package st.btu.trading.pillar.processes

import cats.Functor
import cats.effect.kernel.Ref
import cats.effect.{Async, Resource, Sync, Temporal}
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import fs2.Stream
import sttp.client3.SttpBackend
import st.btu.trading.pillar.gateways.{TgGateway, TgGatewayImpl}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.services.commands.*
import st.btu.trading.pillar.services.{CommandHandleService, CommandHandleServiceImpl}
import tofu.kernel.types.Throws
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import scala.concurrent.duration.*

/** Polls Telegram for updates and runs each command typed or tapped in one of `allowedChats`. A
  * command from any other chat is ignored: it is neither answered nor run, and a warning in the log
  * names its chat. With no allowed chat, no command runs.
  */
final class TgUpdatesProcessImpl[F[_]: Logging](
  tgGateway: TgGateway[F],
  commandHandleService: CommandHandleService[F],
  allowedChats: Set[tg.ChatId]
)(implicit F: Temporal[F])
    extends TgUpdatesProcess[F] {
  import TgUpdatesProcessImpl.ChatCommand

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

  private def processOneUpdate(update: tg.Update): F[Unit] =
    commandOf(update).traverse_(runCommand)

  // A tapped button's command is for the chat of the message its keyboard is attached to. A
  // callback that names its button by inline_message_id instead carries no chat, so it is ignored.
  private def commandOf(update: tg.Update): Option[ChatCommand] =
    update.message.flatMap(m => m.text.map(m.chat.id -> _)) match {
      case Some((chatId, text)) =>
        CommandParser.parse(text).map(ChatCommand(chatId, _, callbackQueryId = None))
      case None =>
        for {
          callback <- update.callback_query
          message <- callback.message
          parsed <- callback.data.flatMap(CommandParser.parse)
        } yield ChatCommand(message.chat.id, parsed, Some(callback.id))
    }

  // The callback is answered first, so the tapped button's loading state clears even if the
  // command is slow or fails.
  private def runCommand(command: ChatCommand): F[Unit] =
    if (allowedChats.contains(command.chatId))
      command.callbackQueryId.traverse_(tgGateway.answerCallbackQuery) >>
        commandHandleService.handleCommand(command.chatId, command.parsed)
    else warn"Ignored a command from chat ${command.chatId}, which may not run commands"
}

object TgUpdatesProcessImpl {

  private final case class ChatCommand(
    chatId: tg.ChatId,
    parsed: ParsedCommand,
    callbackQueryId: Option[String]
  )

  /** Wires the Telegram updates process: a gateway for `tgToken`, the built-in ping and help
    * handlers plus `extraHandlers`, and the command router, running the commands of `allowedChats`
    * only.
    */
  def resource[I[_]: Sync, F[_]: Async: Throws](
    tgToken: tg.Token,
    backend: SttpBackend[F, Any],
    allowedChats: Set[tg.ChatId],
    extraHandlers: List[CommandHandler[F]] = Nil
  )(implicit logs: Logs[I, F]): Resource[I, TgUpdatesProcessImpl[F]] =
    for {
      tgGateway <- Resource.eval(TgGatewayImpl.create[I, F](tgToken, backend))
      commandHandleService <- Resource.eval(
        CommandHandleServiceImpl
          .create[I, F](tgGateway, CommandHandlers.standard(tgGateway, extraHandlers))
      )
      tgUpdatesProcess <- Resource.eval(
        TgUpdatesProcessImpl.create[I, F](tgGateway, commandHandleService, allowedChats)
      )
    } yield tgUpdatesProcess

  /** Wires the Telegram updates process backed by `tgGateway` and `commandHandleService`, running
    * the commands of `allowedChats` only.
    */
  def create[I[_]: Functor, F[_]: Temporal](
    tgGateway: TgGateway[F],
    commandHandleService: CommandHandleService[F],
    allowedChats: Set[tg.ChatId]
  )(implicit logs: Logs[I, F]): I[TgUpdatesProcessImpl[F]] =
    logs
      .forService[TgUpdatesProcess[F]]
      .map(implicit log =>
        new TgUpdatesProcessImpl[F](tgGateway, commandHandleService, allowedChats)
      )
}
