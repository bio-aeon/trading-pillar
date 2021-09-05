package su.wps.trading.pillar.models.tg

import io.circe.generic.JsonCodec

@JsonCodec
final case class Message(message_id: Long, chat: Chat, text: Option[String])
