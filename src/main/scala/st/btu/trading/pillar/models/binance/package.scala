package st.btu.trading.pillar.models

import cats.Show

/** Binance credential types: `X(value)` wraps a value unchecked and `x.value` unwraps it. */
package object binance {

  /** A Binance API key, sent in the `X-MBX-APIKEY` header of signed requests. */
  opaque type ApiKey = String

  object ApiKey {
    def apply(value: String): ApiKey = value
    extension (apiKey: ApiKey) def value: String = apiKey
    implicit val show: Show[ApiKey] = Show.catsShowForString
  }

  /** A Binance secret key, which signs requests with HMAC-SHA256. */
  opaque type SecretKey = String

  object SecretKey {
    def apply(value: String): SecretKey = value
    extension (secretKey: SecretKey) def value: String = secretKey
    implicit val show: Show[SecretKey] = Show.catsShowForString
  }
}
