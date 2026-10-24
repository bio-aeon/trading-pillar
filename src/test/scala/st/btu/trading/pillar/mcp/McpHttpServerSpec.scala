package st.btu.trading.pillar.mcp

import cats.arrow.FunctionK
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.server.{McpServer, RequestContext, ToolFunction}
import com.comcast.ip4s.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.{Accept, Authorization, Host}
import org.http4s.implicits.*
import org.specs2.mutable.Specification
import org.typelevel.ci.CIStringSyntax
import st.btu.trading.pillar.gateways.MarketDataSourcesImpl
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{Source, SourceSetting}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{MarketDataFakes, McpFakes}
import tofu.logging.{Logging, Logs}

class McpHttpServerSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[McpHttpServer])

  private val protocolVersion = "2026-07-28"
  private val token = "s3cret-token"
  private val allowedOrigin = "https://agents.example.org"

  private val config =
    McpHttpConfig(host"127.0.0.1", port"8080", AccessToken(token), Set(allowedOrigin))

  // The tool server of a bot with a Yahoo adapter and nothing stored.
  private def toolServer: IO[McpServer[IO]] =
    for {
      yahoo <- MarketDataFakes.marketDataSource()
      settings <- MarketDataFakes.tickerSourceStorage()
      watchlist <- MarketDataFakes.watchlistStorage()
      priceHistory <- MarketDataFakes.priceHistoryStorage()
      instruments <- MarketDataFakes.instrumentStorage()
      sources = new MarketDataSourcesImpl[IO](
        Some(MarketDataFakes.yahooSymbolsOver(yahoo)),
        None,
        settings,
        SourceSetting.Yahoo(None)
      )
      ports = McpPorts(
        sources,
        settings,
        watchlist,
        priceHistory,
        instruments,
        McpFakes.failingCommandRunner(new AssertionError("ran a command")),
        AccountId(1)
      )
      builder <- McpToolBuilder.create[IO, IO, IO](FunctionK.id[IO])
      tools = McpTools.create(ports, builder)
    } yield new McpToolServer[IO](
      Implementation("example-bot", "1.0"),
      None,
      tools.readOnly ++ tools.additive ++ tools.changing
    )

  // Starts the server with `serverConfig` and gives `use` a client that calls its routes in memory.
  private def withClient[A](serverConfig: McpHttpConfig = config)(use: Client[IO] => IO[A]): IO[A] =
    toolServer.flatMap { server =>
      McpHttpServer
        .resource(server, serverConfig)
        .use(httpServer => use(Client.fromHttpApp(httpServer.routes.orNotFound)))
    }

  private val clientMeta = Json.obj(
    "io.modelcontextprotocol/protocolVersion" -> protocolVersion.asJson,
    "io.modelcontextprotocol/clientCapabilities" -> Json.obj()
  )

  // A request as a client of protocol revision 2026-07-28 sends it.
  private def mcpRequest(
    method: String,
    params: JsonObject = JsonObject.empty,
    name: Option[String] = None,
    bearer: Option[String] = Some(token),
    extraHeaders: List[Header.ToRaw] = Nil
  ): Request[IO] = {
    val body = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> 1.asJson,
      "method" -> method.asJson,
      "params" -> params.add("_meta", clientMeta).asJson
    )
    val optionalHeaders: List[Header.ToRaw] =
      name.map(value => Header.Raw(ci"Mcp-Name", value): Header.ToRaw).toList ++
        bearer
          .map(value => Authorization(Credentials.Token(AuthScheme.Bearer, value)): Header.ToRaw)
          .toList
    Request[IO](Method.POST, uri"/mcp")
      .putHeaders(
        Host("mcp.example.com"),
        Accept(MediaType.application.json, MediaType.`text/event-stream`),
        Header.Raw(ci"MCP-Protocol-Version", protocolVersion),
        Header.Raw(ci"Mcp-Method", method)
      )
      .putHeaders(optionalHeaders*)
      .putHeaders(extraHeaders*)
      .withEntity(body)
  }

  private def send(request: Request[IO]): IO[(Response[IO], Json)] =
    withClient()(client =>
      client.run(request).use(response => response.as[Json].map(response -> _))
    )

  private def status(request: Request[IO]): IO[Status] =
    withClient()(client => client.status(request))

  private def callTicker(ticker: String): Request[IO] =
    mcpRequest(
      "tools/call",
      JsonObject(
        "name" -> "ticker_status".asJson,
        "arguments" -> Json.obj("ticker" -> ticker.asJson)
      ),
      name = Some("ticker_status")
    )

  "McpHttpServer" >> {
    "bearer token" >> {

      "answers a request without a token with 401 and a Bearer challenge" >> {
        withClient()(client =>
          client
            .run(mcpRequest("tools/list", bearer = None))
            .use(response =>
              IO.pure(
                (response.status, response.headers.get(ci"WWW-Authenticate").map(_.head.value))
              )
            )
        ).map(_ must_== ((Status.Unauthorized, Some("Bearer"))))
      }

      "closes the connection after refusing a request, since its body stays unread" >> {
        withClient()(client =>
          client
            .run(mcpRequest("tools/list", bearer = None))
            .use(response => IO.pure(response.headers.get(ci"Connection").map(_.head.value)))
        ).map(_ must beSome("close"))
      }

      "answers a request with a wrong token with 401" >> {
        status(mcpRequest("tools/list", bearer = Some("another-token")))
          .map(_ must_== Status.Unauthorized)
      }

      "answers a request with credentials of another scheme with 401" >> {
        val basic: Header.ToRaw = Authorization(Credentials.Token(AuthScheme.Basic, token))
        status(mcpRequest("tools/list", bearer = None, extraHeaders = List(basic)))
          .map(_ must_== Status.Unauthorized)
      }

      "answers a request to any other path with 401 too" >> {
        status(Request[IO](Method.GET, uri"/somewhere")).map(_ must_== Status.Unauthorized)
      }

      "lets a request with the token through" >> {
        status(mcpRequest("tools/list")).map(_ must_== Status.Ok)
      }
    }

    "tools/list" >> {

      def listed(body: Json): List[Json] =
        body.hcursor.downField("result").downField("tools").values.toList.flatten

      "lists the tools of the server by name" >> {
        send(mcpRequest("tools/list")).map { case (_, body) =>
          listed(body).map(_.hcursor.get[String]("name")) must_== List(
            "bot_backtest",
            "bot_command",
            "bot_query",
            "live_quote",
            "ticker_status"
          ).map(Right(_))
        }
      }

      "describes the input of ticker_status as an object with a required ticker" >> {
        send(mcpRequest("tools/list")).map { case (_, body) =>
          listed(body)
            .find(_.hcursor.get[String]("name") == Right("ticker_status"))
            .flatMap(_.hcursor.downField("inputSchema").focus) must beSome(
            Json.obj(
              "type" -> "object".asJson,
              "properties" -> Json.obj(
                "ticker" -> Json.obj(
                  "type" -> "string".asJson,
                  "description" -> ("The ticker symbol as the bot knows it, for example AAPL or " +
                    "SBER. Case does not matter.").asJson
                )
              ),
              "required" -> Json.arr("ticker".asJson)
            )
          )
        }
      }
    }

    "tools/call" >> {

      "returns the text of the tool's report" >> {
        send(callTicker("AAPL")).map { case (response, body) =>
          (response.status must_== Status.Ok) and
            (body.hcursor.downField("result").downField("content").focus must beSome(
              Json.arr(
                Json.obj(
                  "type" -> "text".asJson,
                  "text" -> """AAPL
                              |Source setting: yahoo (none set for this ticker, so the default)
                              |Adapter: wired (yahoo)
                              |Watchlist: not on the watchlist
                              |Stored bars, latest date: yahoo none, tinkoff none
                              |Metadata: none stored""".stripMargin.asJson
                )
              )
            ))
        }
      }
    }

    "clients of an earlier protocol revision" >> {

      // Such a client opens a session with initialize and sends its id with every later request.
      def legacyRequest(body: Json, session: Option[String]): Request[IO] =
        Request[IO](Method.POST, uri"/mcp")
          .putHeaders(
            Host("mcp.example.com"),
            Accept(MediaType.application.json, MediaType.`text/event-stream`),
            Authorization(Credentials.Token(AuthScheme.Bearer, token))
          )
          .putHeaders(session.map(id => Header.Raw(ci"Mcp-Session-Id", id): Header.ToRaw).toList*)
          .withEntity(body)

      "open a session and are served the tools in it" >> {
        val initialize = Json.obj(
          "jsonrpc" -> "2.0".asJson,
          "id" -> 1.asJson,
          "method" -> "initialize".asJson,
          "params" -> Json.obj(
            "protocolVersion" -> "2025-06-18".asJson,
            "capabilities" -> Json.obj(),
            "clientInfo" -> Json.obj("name" -> "older".asJson, "version" -> "1".asJson)
          )
        )
        val initialized =
          Json.obj("jsonrpc" -> "2.0".asJson, "method" -> "notifications/initialized".asJson)
        val listTools = Json.obj(
          "jsonrpc" -> "2.0".asJson,
          "id" -> 2.asJson,
          "method" -> "tools/list".asJson,
          "params" -> Json.obj()
        )
        withClient() { client =>
          for {
            session <- client
              .run(legacyRequest(initialize, None))
              .use(response => IO.pure(response.headers.get(ci"Mcp-Session-Id").map(_.head.value)))
            _ <- client.status(legacyRequest(initialized, session))
            listed <- client.run(legacyRequest(listTools, session)).use { response =>
              response.as[Json].map(body => (response.status, body))
            }
          } yield (
            session.isDefined,
            listed._1,
            listed._2.hcursor.downField("result").downField("tools").values.map(_.size)
          )
        }.map(_ must_== ((true, Status.Ok, Some(5))))
      }
    }

    "browser origins" >> {

      "refuses an origin that is not allowed with 403" >> {
        val foreign = List[Header.ToRaw](Header.Raw(ci"Origin", "https://evil.example.org"))
        status(mcpRequest("tools/list", extraHeaders = foreign)).map(_ must_== Status.Forbidden)
      }

      "serves an origin that is allowed" >> {
        val allowed = List[Header.ToRaw](Header.Raw(ci"Origin", allowedOrigin))
        status(mcpRequest("tools/list", extraHeaders = allowed)).map(_ must_== Status.Ok)
      }

      "refuses every origin when none is allowed" >> {
        val allowed = List[Header.ToRaw](Header.Raw(ci"Origin", allowedOrigin))
        withClient(config.copy(allowedOrigins = Set.empty))(client =>
          client.status(mcpRequest("tools/list", extraHeaders = allowed))
        ).map(_ must_== Status.Forbidden)
      }

      "serves a request with no origin, as a program sends it" >> {
        withClient(config.copy(allowedOrigins = Set.empty))(client =>
          client.status(mcpRequest("tools/list"))
        ).map(_ must_== Status.Ok)
      }
    }

    "server failures" >> {

      // A server whose tools cannot be listed, a failure outside the tools themselves.
      val brokenServer: McpServer[IO] = new McpServer[IO] with McpServer.ToolProvider[IO] {
        def serverInfo: Implementation = Implementation("broken", "1.0")

        def instructions(context: RequestContext[IO]): IO[Option[String]] = IO.pure(None)

        def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] =
          IO.raiseError(new RuntimeException("tools unavailable"))
      }

      "are logged at error level and answered with an internal error" >> {
        for {
          capturing <- McpFakes.capturingLogs
          (capturedLogs, entries) = capturing
          code <- McpHttpServer
            .resource(brokenServer, config)(using capturedLogs)
            .use { httpServer =>
              Client
                .fromHttpApp(httpServer.routes.orNotFound)
                .run(mcpRequest("tools/list"))
                .use(_.as[Json])
                .map(_.hcursor.downField("error").get[Int]("code"))
            }
          logged <- entries
        } yield (code, logged.count(_.level == Logging.Error)) must_== ((Right(-32603), 1))
      }
    }

    "resource" >> {

      "fails when an allowed origin is not of the form scheme://host[:port]" >> {
        toolServer
          .flatMap(server =>
            McpHttpServer
              .resource(server, config.copy(allowedOrigins = Set("agents.example.org")))
              .use(_ => IO.unit)
              .attempt
          )
          .map(
            _.left.map(_.getMessage) must beLeft(
              "Invalid allowed origin 'agents.example.org': expected scheme://host[:port]"
            )
          )
      }
    }
  }
}
