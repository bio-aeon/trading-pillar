package st.btu.trading.pillar.services

import cats.MonadThrow
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.Functor
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.services.commands.{CommandHandler, CommandReply, ParsedCommand}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

// Routes a parsed command to its registered handler. Unknown commands (and commands with no
// registered handler) get the same "Unknown command." reply; a handler that raises is caught here
// so one bad command never kills the update loop, and the chat gets a short failure reply instead.
final class CommandHandleServiceImpl[F[_]: Logging: MonadThrow](
  tgGateway: TgGateway[F],
  handlers: List[CommandHandler[F]]
) extends CommandHandleService[F] {

  private val handlersByCommand: Map[Command, CommandHandler[F]] =
    handlers.map(h => h.command -> h).toMap

  def handleCommand(chatId: tg.ChatId, parsed: ParsedCommand): F[Unit] =
    handlersByCommand.get(parsed.command) match {
      case Some(handler) => runHandler(chatId, handler, parsed.args)
      case None => handleUnknown(chatId)
    }

  private def runHandler(
    chatId: tg.ChatId,
    handler: CommandHandler[F],
    args: List[String]
  ): F[Unit] =
    handler.handle(chatId, args).handleErrorWith { e =>
      errorCause"Command /${handler.command.value} failed" (e) >> replyFailure(chatId, e)
    }

  private def replyFailure(chatId: tg.ChatId, e: Throwable): F[Unit] =
    tgGateway.sendMessage(chatId, CommandReply.prepare(s"Command failed: ${messageOf(e)}"))

  private def messageOf(e: Throwable): String = Option(e.getMessage).getOrElse(e.toString)

  private def handleUnknown(chatId: tg.ChatId): F[Unit] =
    tgGateway.sendMessage(chatId, CommandReply.prepare("Unknown command. Try /help"))
}

object CommandHandleServiceImpl {

  /** Wires a [[CommandHandleService]] routing to `handlers` by their own `command`. */
  def create[I[_]: Functor, F[_]: MonadThrow](
    tgGateway: TgGateway[F],
    handlers: List[CommandHandler[F]]
  )(implicit logs: Logs[I, F]): I[CommandHandleServiceImpl[F]] =
    logs
      .forService[CommandHandleService[F]]
      .map(implicit log => new CommandHandleServiceImpl[F](tgGateway, handlers))
}
