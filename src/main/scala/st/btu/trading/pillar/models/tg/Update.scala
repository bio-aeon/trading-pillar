package st.btu.trading.pillar.models.tg

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

final case class Update(
  update_id: Long,
  message: Option[Message],
  callback_query: Option[CallbackQuery]
)

object Update {
  implicit val codecForUpdate: Codec.AsObject[Update] = deriveCodec
}
