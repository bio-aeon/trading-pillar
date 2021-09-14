package su.wps.trading.pillar.models

import cats.Show
import io.estatico.newtype.macros.newtype

package object tcs {
  @newtype final case class Token(value: String)

  object Token {
    implicit val show: Show[Token] = deriving
  }

  @newtype final case class BrokerAccountId(value: String)

  object BrokerAccountId {
    implicit val show: Show[BrokerAccountId] = deriving
  }
}
