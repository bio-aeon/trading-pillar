package su.wps.trading.pillar.models

import cats.Show
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.macros.newtype

package object tg {
  @newtype final case class Token(value: String)

  object Token {
    implicit val show: Show[Token] = deriving
  }

  @newtype final case class ChatId(value: Long)

  object ChatId {
    implicit val show: Show[ChatId] = deriving
    implicit val encoder: Encoder[ChatId] = deriving
    implicit val decoder: Decoder[ChatId] = deriving
  }
}
