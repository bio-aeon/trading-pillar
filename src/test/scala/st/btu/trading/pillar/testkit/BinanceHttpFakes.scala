package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import sttp.client3.impl.cats.implicits.*
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{NoBody, Response, SttpBackend}
import sttp.model.StatusCode

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration

/** A stand-in for Binance's servers behind an sttp backend, which records what it was asked. */
object BinanceHttpFakes {

  val ApiHost = "api.binance.com"
  val MarketHost = "data-api.binance.vision"

  /** What the server was asked, with the time the call arrived and how many calls to the path there
    * had been, this one included.
    */
  final case class Call(
    host: String,
    path: String,
    query: String,
    key: Option[String],
    method: String,
    hasBody: Boolean,
    at: FiniteDuration,
    number: Int
  ) {

    /** The parameters as the server reads them, the signature included. */
    def params: Map[String, String] =
      query
        .split("&")
        .filter(_.nonEmpty)
        .map { pair =>
          val Array(name, value) = pair.split("=", 2)
          URLDecoder.decode(name, StandardCharsets.UTF_8) ->
            URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
        .toMap

    /** The query that was signed: all of it up to the signature. */
    def signedQuery: String = query.substring(0, query.lastIndexOf("&signature="))

    def signature: String = query.substring(query.lastIndexOf("&signature=") + "&signature=".length)
  }

  sealed trait Reply

  final case class Responds(body: String, status: StatusCode = StatusCode.Ok) extends Reply

  /** The call fails with `failure`, as it does when the HTTP client cannot reach the server. */
  final case class Breaks(failure: Exception) extends Reply

  def answer(body: String): Reply = Responds(body)

  def refusal(code: Int, message: String, status: StatusCode = StatusCode.BadRequest): Responds =
    Responds(s"""{"code":$code,"msg":"$message"}""", status)

  def statusOf(code: Int): StatusCode = StatusCode.unsafeApply(code)

  final case class Server(backend: SttpBackend[IO, Any], calls: IO[List[Call]])

  /** A server that answers each call with what `respond` makes of it, and the call to
    * `/api/v3/time` with the clock of a server that is `offsets` milliseconds ahead of this one,
    * the next offset after each call and the last again after that.
    */
  def server(
    offsets: List[Long] = List(0L),
    respond: Call => Reply = _ => answer("[]")
  ): IO[Server] =
    Ref.of[IO, List[Call]](List.empty).map { calls =>
      val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest.thenRespondF { request =>
        val path = "/" + request.uri.path.mkString("/")
        val text = request.uri.toString
        for {
          at <- IO.monotonic
          call <- calls.modify { seen =>
            val number = seen.count(_.path == path) + 1
            val made = Call(
              request.uri.host.getOrElse(""),
              path,
              text.split("\\?", 2).lift(1).getOrElse(""),
              request.header("X-MBX-APIKEY"),
              request.method.method,
              request.body != NoBody,
              at,
              number
            )
            (seen :+ made, made)
          }
          reply <-
            if (path == "/api/v3/time") {
              val offset = offsets.lift(call.number - 1).getOrElse(offsets.last)
              IO.realTime.map(now => answer(s"""{"serverTime":${now.toMillis + offset}}"""))
            } else IO.pure(respond(call))
          response <- reply match {
            case Responds(body, status) => IO.pure(Response(body, status))
            case Breaks(failure) => IO.raiseError[Response[String]](failure)
          }
        } yield response
      }
      Server(backend, calls.get)
    }
}
