package st.btu.trading.pillar.gateways.binance

import cats.Functor
import cats.syntax.functor.*
import st.btu.trading.pillar.models.binance.SecretKey
import st.btu.trading.pillar.security.Crypto

import java.nio.charset.StandardCharsets

// The query string of a request. Binance verifies a signature over the percent-encoded string that
// is sent, so the string is built here, once, signed as it is, and sent unchanged.
private[binance] object BinanceQuery {

  private val SignatureParameter = "signature"

  // RFC 3986's unreserved characters, which are never encoded.
  private val Unreserved: Set[Char] = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ "-._~").toSet

  /** `value` with every byte of its UTF-8 form that is not an unreserved character written as `%`
    * and two upper-case hexadecimal digits.
    */
  def percentEncode(value: String): String =
    value
      .getBytes(StandardCharsets.UTF_8)
      .foldLeft(new StringBuilder) { (encoded, byte) =>
        val char = (byte & 0xff).toChar
        if (byte >= 0 && Unreserved(char)) encoded.append(char)
        else encoded.append("%%%02X".format(byte & 0xff))
      }
      .toString

  /** The parameters as `name=value` pairs joined by `&`, in the order given, names and values
    * percent-encoded.
    */
  def encode(parameters: List[(String, String)]): String =
    parameters
      .map { case (name, value) => s"${percentEncode(name)}=${percentEncode(value)}" }
      .mkString("&")

  /** The encoded query of `parameters` followed by `&signature=` and the HMAC-SHA256 of the encoded
    * query, in lower-case hexadecimal, made with `secretKey`.
    */
  def signed[F[_]: Functor](
    parameters: List[(String, String)],
    secretKey: SecretKey,
    crypto: Crypto[F]
  ): F[String] = {
    val query = encode(parameters)
    crypto
      .calcHmacSha256(query, secretKey.value)
      .map(signature => s"$query&$SignatureParameter=$signature")
  }
}
