package su.wps.trading.pillar.gateways

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.{Functor, Monad}
import io.circe.Decoder
import io.circe.parser.decode
import sttp.client3._
import sttp.model.{Header, Uri}
import su.wps.trading.pillar.models.tcs
import tofu.kernel.types.Throws
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._

import java.net.URI

trait TcsGateway[F[_]] {
  def portfolio: F[tcs.Result[tcs.Portfolio]]
}

object TcsGateway {

  def create[I[_]: Functor, F[_]: Monad: Throws](
    brokerAccountId: tcs.BrokerAccountId,
    token: tcs.Token,
    backend: SttpBackend[F, Any]
  )(implicit logs: Logs[I, F]): I[TcsGateway[F]] =
    logs.forService[TcsGateway[F]].map(implicit log => new Impl[F](brokerAccountId, token, backend))

  private final class Impl[F[_]: Logging](
    brokerAccountId: tcs.BrokerAccountId,
    token: tcs.Token,
    backend: SttpBackend[F, Any]
  )(implicit F: Monad[F], R: Throws[F])
      extends TcsGateway[F]
      with GatewayLogging {

    private val endpoint = "https://api-invest.tinkoff.ru/openapi"

    def portfolio: F[tcs.Result[tcs.Portfolio]] =
      sendRequest("portfolio", parseResponse[tcs.Result[tcs.Portfolio]])

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

    private[gateways] def sendRequest[Res](
      path: String,
      handleRes: Response[Either[String, String]] => F[Res]
    ): F[Res] = {
      val req = basicRequest
        .get(Uri(URI.create(s"$endpoint/$path?brokerAccountId=${brokerAccountId.show}")))
        .headers(Header("Authorization", s"Bearer ${token.show}"))

      for {
        _ <- debug"${requestToString(req)}"
        resp <- req.send(backend)
        _ <- debug"${responseToString(resp)}"
        result <- handleRes(resp)
      } yield result
    }
  }
}
