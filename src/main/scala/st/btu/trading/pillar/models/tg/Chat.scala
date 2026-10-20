package st.btu.trading.pillar.models.tg

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Chat(id: ChatId)

object Chat {
  implicit val codecForChat: Codec.AsObject[Chat] = deriveCodec
}
