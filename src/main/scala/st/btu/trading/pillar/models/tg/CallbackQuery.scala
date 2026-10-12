package st.btu.trading.pillar.models.tg

import io.circe.generic.JsonCodec

/** Telegram's `callback_query` update, sent when the user taps an inline keyboard button.
  *
  * @param data
  *   that button's `callbackData`
  * @param message
  *   the message the keyboard was attached to; absent if it has since been deleted or is too old
  *   for Telegram to retain
  */
@JsonCodec
final case class CallbackQuery(
  id: String,
  from: Option[User],
  message: Option[Message],
  data: Option[String]
)
