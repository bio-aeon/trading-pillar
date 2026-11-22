package st.btu.trading.pillar.gateways.binance

import cats.effect.{Async, Ref, Sync, Temporal}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.models.binance.{ApiKey, SecretKey}
import st.btu.trading.pillar.security.Crypto

import scala.concurrent.duration.FiniteDuration

// Puts the key and a signature on the requests of one key. The `timestamp` of a request is the local
// time moved by the offset to Binance's clock, which is read before the first signed call and
// again when Binance refuses a timestamp.
private[binance] final class BinanceSigner[F[_]] private (
  apiKey: ApiKey,
  secretKey: SecretKey,
  recvWindow: FiniteDuration,
  crypto: Crypto[F],
  offset: Ref[F, Option[Long]],
  readServerTime: F[Long]
)(implicit F: Temporal[F]) {
  import BinanceSigner.*

  // The request of a signed call, built as late as possible so that its timestamp is fresh when it
  // leaves.
  def prepare(endpoint: SignedEndpoint, parameters: List[(String, String)]): F[Prepared] =
    for {
      timestamp <- currentTimestamp
      signed <- BinanceQuery.signed(
        parameters ++ List(
          Parameter.RecvWindow -> recvWindow.toMillis.toString,
          Parameter.Timestamp -> timestamp.toString
        ),
        secretKey,
        crypto
      )
    } yield Prepared(BinanceQuery.withQuery(endpoint.uri, signed), Some(apiKey), secretsOf(signed))

  // The offset from the local clock to Binance's, measured at the middle of the call that asks for
  // Binance's time.
  def readOffset: F[Unit] =
    for {
      before <- F.realTime
      serverTime <- readServerTime
      after <- F.realTime
      middle = (before.toMillis + after.toMillis) / 2
      _ <- offset.set(Some(serverTime - middle))
    } yield ()

  private def currentTimestamp: F[Long] =
    offset.get.flatMap {
      case Some(delta) => F.realTime.map(_.toMillis + delta)
      case None => readOffset >> currentTimestamp
    }

  // What an error or a log must not repeat: the query, signed and as it was signed, its signature,
  // and the keys.
  private def secretsOf(signedQuery: String): List[String] = {
    val separator = signedQuery.lastIndexOf(SignatureSeparator)
    val query = signedQuery.substring(0, separator)
    val signature = signedQuery.substring(separator + SignatureSeparator.length)
    List(signedQuery, query, signature, secretKey.value, apiKey.value).filter(_.nonEmpty)
  }
}

private[binance] object BinanceSigner {

  private val SignatureSeparator = "&signature="

  // `readServerTime` asks Binance for its clock, in milliseconds since the epoch.
  def create[I[_]: Sync, F[_]: Async](
    apiKey: ApiKey,
    secretKey: SecretKey,
    recvWindow: FiniteDuration,
    crypto: Crypto[F],
    readServerTime: F[Long]
  ): I[BinanceSigner[F]] =
    Ref
      .in[I, F, Option[Long]](None)
      .map(new BinanceSigner[F](apiKey, secretKey, recvWindow, crypto, _, readServerTime))
}
