package su.wps.trading.pillar.security

trait Crypto[F[_]] {
  def calcHmacSha256(data: String, secret: String): F[String]
}
