package su.wps.trading.pillar.gateways

import cats.effect.Clock
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.{Functor, Monad}
import io.circe.parser.decode
import io.circe.{Decoder, Json}
import sttp.client3.{Response, SttpBackend, basicRequest}
import sttp.model.{Header, Uri}
import su.wps.trading.pillar.models.binance
import su.wps.trading.pillar.security.Crypto
import tofu.kernel.types.Throws
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._

import java.net.URI

trait BinanceGateway[F[_]] {
  def allOrders: F[Json]
}

object BinanceGateway {

  def create[I[_]: Functor, F[_]: Clock: Monad: Throws](
    apiKey: binance.ApiKey,
    secretKey: binance.SecretKey,
    crypto: Crypto[F],
    backend: SttpBackend[F, Any]
  )(implicit logs: Logs[I, F]): I[BinanceGateway[F]] =
    logs
      .forService[BinanceGateway[F]]
      .map(implicit log => new Impl[F](apiKey, secretKey, crypto, backend))

  private final class Impl[F[_]: Logging](
    apiKey: binance.ApiKey,
    secretKey: binance.SecretKey,
    crypto: Crypto[F],
    backend: SttpBackend[F, Any]
  )(implicit F: Monad[F], R: Throws[F], clock: Clock[F])
      extends BinanceGateway[F]
      with GatewayLogging {

    private val endpoint = "https://api.binance.com/api/v3"

    def allOrders: F[Json] =
      for {
        now <- clock.realTime
        queryString = s"symbol=BTCUSDT&timestamp=${now.toMillis}"
        signature <- crypto.calcHmacSha256(queryString, secretKey.show)
        req = basicRequest
          .get(Uri(URI.create(s"$endpoint/allOrders?$queryString&signature=$signature")))
          .headers(Header("X-MBX-APIKEY", apiKey.show))
        _ <- debug"${requestToString(req)}"
        resp <- req.send(backend)
        _ <- debug"${responseToString(resp)}"
        result <- parseResponse[Json](resp)
      } yield result

    private[gateways] def parseResponse[A: Decoder](
      response: Response[Either[String, String]]
    ): F[A] = {
      def parseContent(content: String): F[A] =
        decode[A](content).fold(
          err => R.raise(new Exception(s"Failed to parse response: ${err.getMessage}")),
          F.pure
        )

      if (response.isSuccess) {
        response.body match {
          case Right(content) => parseContent(content)
          case Left(_) => R.raise(new Exception("Empty response body"))
        }
      } else {
        R.raise(new Exception(s"Unexpected response status: ${response.code}"))
      }
    }
  }
}
