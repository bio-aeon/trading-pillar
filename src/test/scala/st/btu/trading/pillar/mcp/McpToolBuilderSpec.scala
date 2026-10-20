package st.btu.trading.pillar.mcp

import cats.~>
import cats.arrow.FunctionK
import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref}
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.server.ToolFunction
import com.melvinlow.json.schema.JsonSchemaEncoder
import com.melvinlow.json.schema.generic.{auto, semiauto}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax.*
import io.circe.{Decoder, Json, JsonObject}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.McpFakes
import st.btu.trading.pillar.testkit.McpFakes.LogEntry
import tofu.logging.{Logging, Logs}

import semiauto.given

// A tool input with two arguments, to show how the builder reads and describes them.
private final case class PairInput(left: String, right: Int)

private object PairInput {
  implicit val schema: JsonSchemaEncoder[PairInput] = auto.derived
  implicit val decoder: Decoder[PairInput] = deriveDecoder
}

class McpToolBuilderSpec extends Specification with CatsEffect {

  private val plainLogs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[McpToolBuilder[IO, IO]])

  private val info =
    ToolFunction.Info("probe", None, None, ToolFunction.Effect.ReadOnly, isOpenWorld = false)

  private def builderOver(
    logs: Logs[IO, IO] = plainLogs,
    run: IO ~> IO = FunctionK.id[IO]
  ): IO[McpToolBuilder[IO, IO]] = {
    implicit val builderLogs: Logs[IO, IO] = logs
    McpToolBuilder.create[IO, IO, IO](run)
  }

  private val pair = JsonObject("left" -> "a".asJson, "right" -> 2.asJson)

  private def answered(text: String): CallTool.Response =
    CallTool.Response.Success(List(Content.Text(text)))

  private def refused(text: String): CallTool.Response =
    CallTool.Response.Error(List(Content.Text(text)))

  "McpToolBuilder" >> {
    "text" >> {

      "answers with the text the work returns" >> {
        for {
          builder <- builderOver()
          tool = builder.text[NoArguments](info)(_ => IO.pure("all good"))
          response <- McpFakes.call(tool)
        } yield response must_== answered("all good")
      }

      "hands the decoded arguments to the work" >> {
        for {
          builder <- builderOver()
          tool = builder.text[PairInput](info)(input => IO.pure(s"${input.left}:${input.right}"))
          response <- McpFakes.callWith(tool, pair)
        } yield response must_== answered("a:2")
      }

      "runs the work through the function it was given" >> {
        for {
          runs <- Ref.of[IO, Int](0)
          counting = new (IO ~> IO) {
            def apply[A](work: IO[A]): IO[A] = runs.update(_ + 1) >> work
          }
          builder <- builderOver(run = counting)
          tool = builder.text[NoArguments](info)(_ => IO.pure("all good"))
          _ <- McpFakes.call(tool)
          count <- runs.get
        } yield count must_== 1
      }
    }

    "content" >> {

      "answers with the content the work returns, images included" >> {
        for {
          builder <- builderOver()
          tool = builder.content[NoArguments](info)(_ =>
            IO.pure(List(Content.Text("chart"), Content.Image(Array[Byte](1, 2), "image/png")))
          )
          response <- McpFakes.call(tool)
        } yield response must beLike {
          case CallTool.Response.Success(
                List(Content.Text(text, _, _), Content.Image(data, mimeType, _, _)),
                _,
                _
              ) =>
            (text must_== "chart") and (mimeType must_== "image/png") and
              (data.toList must_== List[Byte](1, 2))
        }
      }
    }

    "arguments that do not decode" >> {

      "are answered with an error result that names the field" >> {
        for {
          builder <- builderOver()
          tool = builder.text[PairInput](info)(_ => IO.pure("never"))
          missing <- McpFakes.callWith(tool, JsonObject("left" -> "a".asJson))
          wrongType <- McpFakes.callWith(
            tool,
            JsonObject("left" -> "a".asJson, "right" -> "two".asJson)
          )
        } yield (missing, wrongType) must beLike {
          case (
                CallTool.Response.Error(List(Content.Text(missingText, _, _)), _, _),
                CallTool.Response.Error(List(Content.Text(wrongTypeText, _, _)), _, _)
              ) =>
            (missingText must startWith("Invalid arguments:")) and
              (missingText must contain("right")) and
              (wrongTypeText must startWith("Invalid arguments:")) and
              (wrongTypeText must contain("right"))
        }
      }

      "do not run the work" >> {
        for {
          runs <- Ref.of[IO, Int](0)
          builder <- builderOver()
          tool = builder.text[PairInput](info)(_ => runs.update(_ + 1).as("never"))
          _ <- McpFakes.callWith(tool, JsonObject("left" -> "a".asJson))
          count <- runs.get
        } yield count must_== 0
      }
    }

    "input schema" >> {

      "describes the input as an object with its properties, all of them required" >> {
        for {
          builder <- builderOver()
          tool = builder.text[PairInput](info)(_ => IO.pure("never"))
        } yield Json.fromJsonObject(tool.argsSchema) must_== Json.obj(
          "type" -> "object".asJson,
          "properties" -> Json.obj(
            "left" -> Json.obj("type" -> "string".asJson),
            "right" -> Json.obj("type" -> "integer".asJson)
          ),
          "required" -> Json.arr("left".asJson, "right".asJson)
        )
      }

      "marks nothing required for a tool without arguments" >> {
        for {
          builder <- builderOver()
          tool = builder.text[NoArguments](info)(_ => IO.pure("all good"))
        } yield Json.fromJsonObject(tool.argsSchema) must_== Json.obj(
          "type" -> "object".asJson,
          "properties" -> Json.obj()
        )
      }

      "accepts a call without arguments for a tool without arguments" >> {
        for {
          builder <- builderOver()
          tool = builder.text[NoArguments](info)(_ => IO.pure("all good"))
          response <- McpFakes.callWith(tool, JsonObject.empty)
        } yield response must_== answered("all good")
      }
    }

    "tool errors" >> {

      "are answered with an error result carrying the error's content" >> {
        for {
          builder <- builderOver()
          tool = builder.text[NoArguments](info)(_ =>
            IO.raiseError(ToolFunction.ToolError(List(Content.Text("nope"))))
          )
          response <- McpFakes.call(tool)
        } yield response must_== refused("nope")
      }

      "are not logged as errors" >> {
        for {
          capturing <- McpFakes.capturingLogs
          (logs, entries) = capturing
          builder <- builderOver(logs)
          tool = builder.text[NoArguments](info)(_ =>
            IO.raiseError(ToolFunction.ToolError(List(Content.Text("nope"))))
          )
          _ <- McpFakes.call(tool)
          logged <- entries
        } yield logged.map(_.level) must_== List(Logging.Info)
      }
    }

    "unexpected failures" >> {

      "are answered with an error result naming the tool and the failure's message" >> {
        for {
          builder <- builderOver()
          tool = builder.text[NoArguments](info)(_ =>
            IO.raiseError(new RuntimeException("db down"))
          )
          response <- McpFakes.call(tool)
        } yield response must_== refused("probe failed: db down")
      }

      "are described by their class when they have no message" >> {
        for {
          builder <- builderOver()
          tool = builder.text[NoArguments](info)(_ => IO.raiseError(new IllegalStateException()))
          response <- McpFakes.call(tool)
        } yield response must_== refused("probe failed: java.lang.IllegalStateException")
      }

      "are logged once at error level" >> {
        for {
          capturing <- McpFakes.capturingLogs
          (logs, entries) = capturing
          builder <- builderOver(logs)
          tool = builder.text[NoArguments](info)(_ =>
            IO.raiseError(new RuntimeException("db down"))
          )
          _ <- McpFakes.call(tool)
          logged <- entries
        } yield logged.filter(_.level == Logging.Error) must_== List(
          LogEntry(Logging.Error, "MCP tool probe failed")
        )
      }
    }

    "calls" >> {

      "are logged once at info level with the tool's name and its arguments as compact JSON" >> {
        for {
          capturing <- McpFakes.capturingLogs
          (logs, entries) = capturing
          builder <- builderOver(logs)
          tool = builder.text[PairInput](info)(_ => IO.pure("done"))
          _ <- McpFakes.callWith(tool, pair)
          logged <- entries
        } yield logged must_== List(
          LogEntry(Logging.Info, """MCP tool call probe with arguments {"left":"a","right":2}""")
        )
      }

      "are logged even when their arguments do not decode" >> {
        for {
          capturing <- McpFakes.capturingLogs
          (logs, entries) = capturing
          builder <- builderOver(logs)
          tool = builder.text[PairInput](info)(_ => IO.pure("never"))
          _ <- McpFakes.callWith(tool, JsonObject("left" -> "a".asJson))
          logged <- entries
        } yield logged must_== List(
          LogEntry(Logging.Info, """MCP tool call probe with arguments {"left":"a"}""")
        )
      }
    }
  }
}
