package st.btu.trading.pillar.models

import cats.Show
import io.circe.{Decoder, Encoder}

/** Telegram identifier types: `X(value)` wraps a value unchecked and `x.value` unwraps it. */
package object tg {

  /** A Telegram bot token, the secret the Bot API authenticates the bot with. */
  opaque type Token = String

  object Token {
    def apply(value: String): Token = value
    extension (token: Token) def value: String = token
    implicit val show: Show[Token] = Show.catsShowForString
  }

  /** The id of a Telegram chat, where the bot's messages go. */
  opaque type ChatId = Long

  object ChatId {
    def apply(value: Long): ChatId = value
    extension (chatId: ChatId) def value: Long = chatId
    implicit val show: Show[ChatId] = Show.catsShowForLong
    implicit val encoder: Encoder[ChatId] = Encoder.encodeLong
    implicit val decoder: Decoder[ChatId] = Decoder.decodeLong
  }
}
