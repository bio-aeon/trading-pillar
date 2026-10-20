package st.btu.trading.pillar.services.commands

import cats.effect.kernel.Outcome
import cats.effect.syntax.monadCancel.*
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.gateways.{CapturedReply, CapturingTgGateway}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.services.{CommandHandleService, CommandHandleServiceImpl}
import tofu.logging.Logs

/** Why a line could not be run as a command. */
sealed trait CommandRunError {

  /** What went wrong, in words for the caller of the line. */
  def message: String
}

object CommandRunError {

  /** The line does not start with `/`, so it is not a command. */
  final case class NotACommand(line: String) extends CommandRunError {
    def message: String = "Not a command: a command line starts with '/', for example /help"
  }
}

/** Runs command lines through the same handlers as Telegram messages and returns what the handlers
  * replied instead of sending it.
  */
trait CommandRunner[F[_]] {

  /** Runs `line` as a chat message would be, e.g. `/positions` or `/source AAPL tinkoff`.
    *
    * @return
    *   the replies in the order the handlers sent them, which for a command nobody handles is the
    *   "Unknown command" reply and for a handler that fails the "Command failed" reply; or
    *   `CommandRunError.NotACommand` if `line` is not a command line
    */
  def run(line: String): F[Either[CommandRunError, List[CapturedReply]]]

  /** Runs `command` like [[run]], for a line already parsed with [[CommandRunner.parse]]. */
  def runParsed(command: ParsedCommand): F[List[CapturedReply]]
}

object CommandRunner {

  /** Parses `line` as a command line, ignoring whitespace around it, so a caller can look at the
    * command, for instance with [[CommandEffect]], before running it.
    */
  def parse(line: String): Either[CommandRunError, ParsedCommand] =
    CommandParser.parse(line.trim).toRight(CommandRunError.NotACommand(line))
}

/** A [[CommandRunner]] over the handlers behind a
  * [[st.btu.trading.pillar.gateways.CapturingTgGateway]].
  *
  * Every run answers to a chat of its own, so runs in flight at the same time never see each
  * other's replies.
  */
final class CommandRunnerImpl[F[_]: Sync] private (
  gateway: CapturingTgGateway[F],
  commandHandleService: CommandHandleService[F],
  nextChatId: Ref[F, Long]
) extends CommandRunner[F] {

  def run(line: String): F[Either[CommandRunError, List[CapturedReply]]] =
    CommandRunner.parse(line).traverse(runParsed)

  def runParsed(command: ParsedCommand): F[List[CapturedReply]] =
    nextChatId.getAndUpdate(_ + 1).map(tg.ChatId(_)).flatMap { chatId =>
      commandHandleService
        .handleCommand(chatId, command)
        .guaranteeCase {
          case Outcome.Succeeded(_) => Sync[F].unit
          case Outcome.Errored(_) | Outcome.Canceled() => gateway.take(chatId).void
        } >> gateway.take(chatId)
    }
}

object CommandRunnerImpl {

  /** Chat ids count up from the bottom of the `Long` range, which Telegram never assigns to a chat,
    * so a synthetic id cannot name a real chat even if a handler were to remember it.
    */
  private val FirstChatId = Long.MinValue

  /** Wires a [[CommandRunner]] that routes to `handlers` and collects their replies from `gateway`,
    * which the handlers must have been built over.
    */
  def create[I[_]: Sync, F[_]: Sync](
    gateway: CapturingTgGateway[F],
    handlers: List[CommandHandler[F]]
  )(implicit logs: Logs[I, F]): I[CommandRunner[F]] =
    for {
      nextChatId <- Ref.in[I, F, Long](FirstChatId)
      commandHandleService <- CommandHandleServiceImpl.create[I, F](gateway, handlers)
    } yield new CommandRunnerImpl[F](gateway, commandHandleService, nextChatId)
}
