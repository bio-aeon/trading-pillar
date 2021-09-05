package su.wps.trading.pillar.models.tg

import io.circe.generic.JsonCodec

@JsonCodec
final case class Chat(id: ChatId)
