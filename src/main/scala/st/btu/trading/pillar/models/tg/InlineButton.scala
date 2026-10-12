package st.btu.trading.pillar.models.tg

// No JSON codec: TgGatewayImpl renders this into Telegram's own `{"text":..., "callback_data":...}`
// shape by hand (the field is named `callbackData` here for Scala ergonomics, not
// `callback_data`, so circe's generic derivation would produce the wrong JSON key).
/** One inline-keyboard button: the label shown to the user, and the opaque payload Telegram sends
  * back in `CallbackQuery.data` when it's tapped.
  */
final case class InlineButton(text: String, callbackData: String)
