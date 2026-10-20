package st.btu.trading.pillar.mcp

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.either.*
import cats.syntax.traverse.*
import ch.linkyard.mcp.jsonrpc2.transport.http4s.{
  McpServerRoute,
  McpServerRouteConfig,
  SessionStore
}
import ch.linkyard.mcp.server.McpServer.*
import ch.linkyard.mcp.server.{McpServer, McpServerConfig}
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Origin
import org.http4s.server.Server
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import scala.concurrent.duration.*

/** An MCP server served over HTTP (the Streamable HTTP transport, at `/mcp`) behind a bearer token.
  *
  * Every request must carry `Authorization: Bearer <token>` with the configured token, or it is
  * answered with 401. A request from a browser must also come from one of the configured origins,
  * or it is answered with 403. Failures the MCP server answers with an internal error are logged at
  * error level.
  *
  * It runs on `IO`, the only effect the HTTP transport supports. A consumer whose base effect `I`
  * is not `IO` lifts the resource that builds and serves it with `cats.effect.LiftIO`, by calling
  * `.mapK(LiftIO.liftK[I])` on `McpHttpServer.resource(...).flatMap(_.serve)`.
  */
final class McpHttpServer private (
  server: McpServer[IO],
  config: McpHttpConfig,
  originAllowed: Origin => Boolean,
  sessions: SessionStore[IO]
)(implicit log: Logging[IO]) {

  /** The MCP routes with the bearer-token check in front of them: what [[serve]] serves, for a
    * consumer that serves its routes on a server of its own.
    */
  val routes: HttpRoutes[IO] = {
    val factory = server.handlerFactory(McpServerConfig.default, logError)
    val mcp = McpServerRoute.route(factory, McpServerRouteConfig(originAllowed = originAllowed))(
      using sessions
    )
    BearerAuth(config.token)(mcp)
  }

  /** Starts an HTTP server on the configured host and port that serves [[routes]].
    *
    * When the resource is released the server waits at most five seconds for open connections, such
    * as a client's event stream, before it closes them.
    */
  def serve: Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(config.host)
      .withPort(config.port)
      .withShutdownTimeout(McpHttpServer.ShutdownTimeout)
      .withHttpApp(routes.orNotFound)
      .build

  private def logError(error: Throwable): IO[Unit] =
    errorCause"Error while handling an MCP request" (error)
}

object McpHttpServer {

  // How long a session of a client that opened one stays valid without a request. Only clients of
  // older protocol revisions open sessions.
  private val SessionIdleTimeout = 30.minutes

  // Ember's default of 30 s outlasts a container's stop grace period when a client holds a stream.
  private val ShutdownTimeout = 5.seconds

  /** Builds an HTTP server for `server`, to be started with [[McpHttpServer.serve]]. It holds the
    * sessions of clients of older protocol revisions until the resource is released.
    *
    * The resource fails if an entry of `config.allowedOrigins` is not of the form
    * `scheme://host[:port]`.
    */
  def resource(server: McpServer[IO], config: McpHttpConfig)(implicit
    logs: Logs[IO, IO]
  ): Resource[IO, McpHttpServer] =
    for {
      originAllowed <- Resource.eval(IO.fromEither(originPredicate(config.allowedOrigins)))
      sessions <- SessionStore.inMemory[IO](SessionIdleTimeout)
      httpServer <- Resource.eval(
        logs
          .forService[McpHttpServer]
          .map(implicit log => new McpHttpServer(server, config, originAllowed, sessions))
      )
    } yield httpServer

  private def originPredicate(allowed: Set[String]): Either[Throwable, Origin => Boolean] =
    allowed.toList.traverse(parseOrigin).map(_.toSet).map { hosts =>
      {
        case Origin.HostList(requested) => requested.forall(hosts.contains)
        case Origin.Null => false
      }
    }

  private def parseOrigin(text: String): Either[Throwable, Origin.Host] =
    Origin.parse(text) match {
      case Right(Origin.HostList(NonEmptyList(host, Nil))) => host.asRight
      case _ =>
        new IllegalArgumentException(
          s"Invalid allowed origin '$text': expected scheme://host[:port]"
        ).asLeft
    }
}
