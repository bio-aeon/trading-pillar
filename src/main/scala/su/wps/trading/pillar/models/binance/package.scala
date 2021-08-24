package su.wps.trading.pillar.models

import cats.Show
import io.estatico.newtype.macros.newtype

package object binance {
  @newtype final case class ApiKey(value: String)

  object ApiKey {
    implicit val show: Show[ApiKey] = deriving
  }

  @newtype final case class SecretKey(value: String)

  object SecretKey {
    implicit val show: Show[SecretKey] = deriving
  }
}
