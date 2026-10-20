package st.btu.trading.pillar.models

import cats.Show

/** Tinkoff Invest identifier types: `X(value)` wraps a value unchecked and `x.value` unwraps it.
  */
package object tcs {

  /** A Tinkoff Invest API token, the secret sent as the bearer token of every gRPC call. */
  opaque type Token = String

  object Token {
    def apply(value: String): Token = value
    extension (token: Token) def value: String = token
    implicit val show: Show[Token] = Show.catsShowForString
  }

  /** A Tinkoff brokerage account id: the account whose portfolio and operations are read. */
  opaque type BrokerAccountId = String

  object BrokerAccountId {
    def apply(value: String): BrokerAccountId = value
    extension (brokerAccountId: BrokerAccountId) def value: String = brokerAccountId
    implicit val show: Show[BrokerAccountId] = Show.catsShowForString
  }
}
