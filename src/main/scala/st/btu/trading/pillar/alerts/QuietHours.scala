package st.btu.trading.pillar.alerts

import io.circe.generic.JsonCodec

import java.time.{LocalTime, ZoneId}

/** A daily window, in `zone` local time, during which non-system alerts are suppressed. An
  * overnight window (`start` after `end`, e.g. 22:00-07:00) wraps past midnight.
  */
@JsonCodec
final case class QuietHours(start: LocalTime, end: LocalTime, zone: ZoneId)
