package st.btu.trading.pillar.models.tg

import io.circe.generic.JsonCodec

/** Minimal Telegram user: only what `CallbackQuery.from` needs to decode. */
@JsonCodec
final case class User(id: Long)
