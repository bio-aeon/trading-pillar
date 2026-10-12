package st.btu.trading.pillar.models.tg

import io.circe.generic.JsonCodec

@JsonCodec
final case class Result[A](ok: Boolean, result: A)
