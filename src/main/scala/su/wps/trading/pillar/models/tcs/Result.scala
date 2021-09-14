package su.wps.trading.pillar.models.tcs

import io.circe.generic.JsonCodec

@JsonCodec
final case class Result[A](trackingId: String, status: String, payload: A)
