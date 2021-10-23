package su.wps.trading.pillar.gateways

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.{Functor, Monad}
import io.circe.Decoder
import io.circe.parser.decode
import sttp.client3._
import sttp.model.Uri
import su.wps.trading.pillar.models.tg
import tofu.kernel.types.Throws
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._

import java.net.{URI, URLEncoder}

trait TgGateway[F[_]] {
  def sendMessage(chatId: tg.ChatId, msg: String): F[Unit]

  def getUpdates(offset: Long): F[tg.Result[List[tg.Update]]]
}

object TgGateway {

  def create[I[_]: Functor, F[_]: Monad: Throws](token: tg.Token, backend: SttpBackend[F, Any])(
    implicit logs: Logs[I, F]
  ): I[TgGateway[F]] =
    logs.forService[TgGateway[F]].map(implicit log => new Impl[F](token, backend))

  private final class Impl[F[_]: Logging](token: tg.Token, backend: SttpBackend[F, Any])(
    implicit F: Monad[F],
    R: Throws[F]
  ) extends TgGateway[F]
      with GatewayLogging {
    def sendMessage(chatId: tg.ChatId, msg: String): F[Unit] = {
      val queryString =
        s"chat_id=${chatId.show}&parse_mode=Markdown&text=${URLEncoder.encode(msg, "UTF-8")}"
      sendRequest("sendMessage", queryString, checkStatus)
    }

    def getUpdates(offset: Long): F[tg.Result[List[tg.Update]]] = {
      val queryString =
        s"offset=${offset + 1}&timeout=0.5&allowed_updates=${URLEncoder.encode("""["message"]""", "UTF-8")}"
      sendRequest("getUpdates", queryString, parseResponse[tg.Result[List[tg.Update]]])
    }

    private[gateways] def parseResponse[A: Decoder](
      response: Response[Either[String, String]]
    ): F[A] = {
      def parseContent(content: String): F[A] =
        decode[A](content)
          .fold(err => R.raise(new Exception(s"Failed to parse response: $err")), F.pure)

      if (response.isSuccess) {
        response.body match {
          case Right(content) => parseContent(content)
          case Left(_) => R.raise(new Exception("Empty response body"))
        }
      } else {
        R.raise(new Exception(s"Unexpected response status: ${response.code}"))
      }
    }

    private[gateways] def checkStatus(response: Response[Either[String, String]]): F[Unit] =
      Either
        .cond(
          response.isSuccess,
          F.unit,
          R.raise[Unit](new Exception(s"Unexpected response status: ${response.code}"))
        )
        .merge

    private[gateways] def sendRequest[Res](
      path: String,
      queryString: String,
      handleRes: Response[Either[String, String]] => F[Res]
    ): F[Res] = {
      val req = basicRequest
        .get(Uri(URI.create(s"https://api.telegram.org/bot${token.show}/$path?$queryString")))

      for {
        _ <- debug"${requestToString(req)}"
        resp <- req.send(backend)
        _ <- debug"${responseToString(resp)}"
        result <- handleRes(resp)
      } yield result
    }
  }
}
