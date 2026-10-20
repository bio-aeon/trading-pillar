package st.btu.trading.pillar.models.tg

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** Minimal Telegram user: only what `CallbackQuery.from` needs to decode. */
final case class User(id: Long)

object User {
  implicit val codecForUser: Codec.AsObject[User] = deriveCodec
}
