package st.btu.trading.pillar.alerts

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.time.{LocalTime, ZoneId}

/** A daily window, in `zone` local time, during which non-system alerts are suppressed. An
  * overnight window (`start` after `end`, e.g. 22:00-07:00) wraps past midnight.
  */
final case class QuietHours(start: LocalTime, end: LocalTime, zone: ZoneId)

object QuietHours {

  /** The JSON form stored rules and bindings hold, such as
    * `{"start":"22:00:00","end":"07:00:00","zone":"Europe/Moscow"}`. Renaming a field breaks stored
    * data.
    */
  implicit val codecForQuietHours: Codec.AsObject[QuietHours] = deriveCodec
}
