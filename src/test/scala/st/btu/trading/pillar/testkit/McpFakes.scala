package st.btu.trading.pillar.testkit

import cats.Applicative
import cats.effect.{Clock, IO, Ref}
import ch.linkyard.mcp.jsonrpc2.{Authentication, JsonRpcConnection}
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.protocol.{ClientCapabilities, Meta}
import ch.linkyard.mcp.server.*
import io.circe.{Json, JsonObject}
import st.btu.trading.pillar.gateways.CapturedReply
import st.btu.trading.pillar.services.commands.{CommandRunError, CommandRunner, ParsedCommand}
import tofu.logging.{LoggedValue, Logging, Logs}

import java.time.Instant
import java.util.regex.Matcher
import scala.concurrent.duration.*

object McpFakes {

  /** What a caller of a tool sees when it asks the server for a tool's work. */
  val requestContext: RequestContext[IO] = new RequestContext[IO] {
    def client: ClientInfo = ClientInfo(None, ClientCapabilities.empty)
    def authentication: Authentication = Authentication.Anonymous
    def transport: JsonRpcConnection.Info = JsonRpcConnection.Info.Other(Map.empty)
    def meta: Meta = Meta.empty
    def input: InputContext = InputContext.empty
    def reportProgress(progress: Double, total: Option[Double], message: Option[String]): IO[Unit] =
      IO.unit
  }

  // Calls `tool` with string arguments, as the server does after reading them from a request, and
  // returns the response the tool completes with.
  def call(tool: ToolFunction[IO], arguments: (String, String)*): IO[CallTool.Response] =
    callWith(
      tool,
      JsonObject.fromIterable(arguments.map { case (name, value) =>
        name -> Json.fromString(value)
      })
    )

  // The same with arguments of any JSON type.
  def callWith(tool: ToolFunction[IO], arguments: JsonObject): IO[CallTool.Response] =
    tool(arguments, requestContext).flatMap {
      case Outcome.Complete(response) => IO.pure(response)
      case Outcome.InputRequired(_, _) =>
        IO.raiseError(new AssertionError("the tool asked for input"))
    }

  // A clock that always reads `now`.
  def clockAt(now: Instant): Clock[IO] = new Clock[IO] {
    def applicative: Applicative[IO] = Applicative[IO]
    def monotonic: IO[FiniteDuration] = IO.pure(Duration.Zero)
    def realTime: IO[FiniteDuration] =
      IO.pure(FiniteDuration(now.getEpochSecond, SECONDS) + now.getNano.nanos)
  }

  // A runner that replies `replies` to every command line and remembers the lines it ran.
  trait FakeCommandRunner extends CommandRunner[IO] {
    def lines: IO[List[ParsedCommand]]
  }

  def commandRunner(replies: List[CapturedReply]): IO[FakeCommandRunner] =
    Ref.of[IO, List[ParsedCommand]](Nil).map { ran =>
      new FakeCommandRunner {
        def run(line: String): IO[Either[CommandRunError, List[CapturedReply]]] =
          CommandRunner.parse(line) match {
            case Right(parsed) => runParsed(parsed).map(Right(_))
            case Left(error) => IO.pure(Left(error))
          }

        def runParsed(command: ParsedCommand): IO[List[CapturedReply]] =
          ran.update(_ :+ command).as(replies)

        def lines: IO[List[ParsedCommand]] = ran.get
      }
    }

  // A runner whose every run fails with `failure`.
  def failingCommandRunner(failure: Throwable): CommandRunner[IO] = new CommandRunner[IO] {
    def run(line: String): IO[Either[CommandRunError, List[CapturedReply]]] =
      IO.raiseError(failure)

    def runParsed(command: ParsedCommand): IO[List[CapturedReply]] = IO.raiseError(failure)
  }

  // `message` is the entry as it reads once its values are in place.
  final case class LogEntry(level: Logging.Level, message: String)

  // Logs that record every entry, together with a way to read them back.
  def capturingLogs: IO[(Logs[IO, IO], IO[List[LogEntry]])] =
    Ref.of[IO, List[LogEntry]](Nil).map { entries =>
      val logging = new Logging[IO] {
        def write(level: Logging.Level, message: String, values: LoggedValue*): IO[Unit] =
          entries.update(_ :+ LogEntry(level, withValues(message, values)))
      }
      (Logs.const[IO, IO](logging), entries.get)
    }

  // tofu hands over the message with a `{}` for each interpolated value, and the values apart.
  private def withValues(message: String, values: Seq[LoggedValue]): String =
    values.foldLeft(message)((text, value) =>
      text.replaceFirst("\\{\\}", Matcher.quoteReplacement(value.toString))
    )
}
