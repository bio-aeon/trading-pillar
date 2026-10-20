package st.btu.trading.pillar.models.tg

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

final case class Result[A](ok: Boolean, result: A)

object Result {
  implicit def decodeResult[A: Decoder]: Decoder[Result[A]] = deriveDecoder
  implicit def encodeResult[A: Encoder]: Encoder.AsObject[Result[A]] = deriveEncoder
}
