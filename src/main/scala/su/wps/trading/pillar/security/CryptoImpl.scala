package su.wps.trading.pillar.security

import cats.{Applicative, Monad}
import mouse.any._
import tofu.Raise.ContravariantRaise
import tofu.kernel.types.Throws

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.control.NonFatal

final class CryptoImpl[F[_]: Monad] private (implicit R: Throws[F]) extends Crypto[F] {
  val AlgHmacSha256 = "HMACSHA256"

  def calcHmacSha256(data: String, secret: String): F[String] =
    catchNonFatal {
      val key = new SecretKeySpec(secret.getBytes, AlgHmacSha256)
      Mac.getInstance(AlgHmacSha256) <| (_.init(key)) |> (_.doFinal(data.getBytes)
        .map("%02x".format(_))
        .mkString)
    }(identity)

  private[security] def catchNonFatal[F[_], A, E](
    a: => A
  )(f: Throwable => E)(implicit A: Applicative[F], R: ContravariantRaise[F, E]): F[A] =
    try A.pure(a)
    catch {
      case NonFatal(ex) => R.raise(f(ex))
    }
}

object CryptoImpl {

  def create[F[_]: Monad: Throws](implicit B: Bouncy): CryptoImpl[F] =
    new CryptoImpl[F]
}
