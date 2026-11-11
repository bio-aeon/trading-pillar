package st.btu.trading.pillar.models.ibkr

import java.time.{Instant, LocalDate, ZoneId}

/** The positions an IB statement reports as open at the end of its last day.
  *
  * @param day
  *   the last day the statement covers
  * @param zone
  *   the time zone `day` is a day of, which is the zone of the times in the statements
  * @param positions
  *   the positions of the statement's Open Positions section, of every asset category
  */
final case class IbkrOpenPositions(
  day: LocalDate,
  zone: ZoneId,
  positions: List[IbkrOpenPosition]
) {

  /** The instant `day` ends in `zone`: the positions are those that the operations occurring before
    * it leave.
    */
  def end: Instant = day.plusDays(1).atStartOfDay(zone).toInstant
}
