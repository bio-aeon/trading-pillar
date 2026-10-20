package st.btu.trading.pillar.mcp

import cats.{Functor, MonadThrow, ~>}
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import ch.linkyard.mcp.protocol.{Content, Meta}
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.server.{Outcome, ToolFunction}
import com.melvinlow.json.schema.JsonSchemaEncoder
import io.circe.{Decoder, Json, JsonObject}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

/** Builds MCP tools whose work is in the bot's effect `F` and which run in `G`, the effect the
  * server runs in, and gives each of them the conventions of this library's tools:
  *
  *   - The call is logged at info with the tool's name and its arguments as compact JSON, whether
  *     or not they decode, so a tool should not take a secret as an argument.
  *   - Arguments that do not decode into the tool's input are answered with an error result that
  *     starts with `Invalid arguments`, and the work does not run.
  *   - A `ToolFunction.ToolError` raised by the work is answered with an error result carrying its
  *     content, and is not logged: raise one for a problem the caller can read and correct.
  *   - Any other failure of the work, such as a store that is down, is logged once at error level
  *     and answered with an error result that carries the failure's message.
  *   - The input schema marks every property required, so an input case class has no optional
  *     fields. A tool without arguments takes [[NoArguments]].
  *
  * The logging happens in `F`, so the entries carry the trace context of the call. `run` runs the
  * call in `G`: with the HTTP transport `G` is `IO`, and for a consumer whose base effect `I` is
  * not `IO`, `run` is built on [[EffectBridge.toIO]].
  */
final class McpToolBuilder[F[_], G[_]] private (run: F ~> G)(implicit
  F: MonadThrow[F],
  G: Functor[G],
  log: Logging[F]
) {

  /** A tool whose result is the text `work` returns for the decoded arguments. */
  def text[A: JsonSchemaEncoder: Decoder](info: ToolFunction.Info)(
    work: A => F[String]
  ): ToolFunction[G] =
    content[A](info)(input => work(input).map(text => List(Content.Text(text))))

  /** A tool whose result is the content `work` returns for the decoded arguments, which can include
    * images.
    */
  def content[A: JsonSchemaEncoder: Decoder](
    info: ToolFunction.Info
  )(work: A => F[List[Content]]): ToolFunction[G] =
    ToolFunction.native[G](
      info,
      McpToolBuilder.requiredInput[A],
      (arguments, _) =>
        run(respond[A](info.name, arguments)(work))
          .map(response => Outcome.Complete(response): Outcome[CallTool.Response])
    )

  private def respond[A: Decoder](tool: String, arguments: JsonObject)(
    work: A => F[List[Content]]
  ): F[CallTool.Response] =
    logCall(tool, arguments) >> {
      arguments.toJson.as[A] match {
        case Right(input) =>
          work(input)
            .map(result => CallTool.Response.Success(result): CallTool.Response)
            .recoverWith {
              case ToolFunction.ToolError(result, meta) => F.pure(failed(result, meta))
              case failure =>
                errorCause"MCP tool $tool failed" (failure) >>
                  F.pure(failed(List(Content.Text(failureText(tool, failure))), Meta.empty))
            }
        case Left(error) =>
          F.pure(failed(List(Content.Text(s"Invalid arguments: ${error.getMessage}")), Meta.empty))
      }
    }

  private def logCall(tool: String, arguments: JsonObject): F[Unit] = {
    val shown = arguments.toJson.noSpaces
    info"MCP tool call $tool with arguments $shown"
  }

  private def failed(content: List[Content], meta: Meta): CallTool.Response =
    CallTool.Response.Error(content, None, meta)

  private def failureText(tool: String, failure: Throwable): String =
    s"$tool failed: ${Option(failure.getMessage).getOrElse(failure.toString)}"
}

object McpToolBuilder {

  /** Wires a builder in the base effect `I`; its tools run their work in `G` through `run`. */
  def create[I[_]: Functor, F[_]: MonadThrow, G[_]: Functor](run: F ~> G)(implicit
    logs: Logs[I, F]
  ): I[McpToolBuilder[F, G]] =
    logs.forService[McpToolBuilder[F, G]].map(implicit logging => new McpToolBuilder[F, G](run))

  // Marks every property required, as no input has an optional field: revisit when one gets one.
  // A schema without properties gets no list, as an empty one is invalid in older JSON Schema.
  private def requiredInput[A: JsonSchemaEncoder]: JsonObject = {
    val schema = JsonSchemaEncoder[A].schema.asObject.getOrElse(JsonObject.empty)
    val properties = schema("properties").flatMap(_.asObject).toList.flatMap(_.keys)
    if (properties.isEmpty) schema
    else schema.add("required", Json.fromValues(properties.map(Json.fromString)))
  }
}
