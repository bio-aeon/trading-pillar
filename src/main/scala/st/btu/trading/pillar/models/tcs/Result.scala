package st.btu.trading.pillar.models.tcs

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

final case class Result[A](trackingId: String, status: String, payload: A)

object Result {
  implicit def decodeResult[A: Decoder]: Decoder[Result[A]] = deriveDecoder
  implicit def encodeResult[A: Encoder]: Encoder.AsObject[Result[A]] = deriveEncoder
}
