package st.btu.trading.pillar.models.tg

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Message(message_id: Long, chat: Chat, text: Option[String])

object Message {
  implicit val codecForMessage: Codec.AsObject[Message] = deriveCodec
}
