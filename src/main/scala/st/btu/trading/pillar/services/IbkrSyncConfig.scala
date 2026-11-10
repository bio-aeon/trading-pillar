package st.btu.trading.pillar.services

import java.time.{LocalDate, ZoneId}

/** What differs between deployments in how an IB account's statements are synced.
  *
  * @param historyStart
  *   the earliest report date the first sync of an account with no stored row reads. `None` reads
  *   from the earliest date IB keeps for saved Flex Queries, 1 January of the year
  *   [[IbkrSyncConfig.HistoryYears]] before the current one. A date before that is read from there
  *   anyway. Set the day the account opened to start there, since what IB answers for dates before
  *   an account existed is not documented.
  * @param rereadDays
  *   how many days before the latest stored report date every later sync starts reading, so that a
  *   row IB reports late, or corrects, is stored again
  * @param windowDays
  *   how many days of report dates one statement is asked for; a statement of a longer window can
  *   fail to generate
  * @param statementZone
  *   the time zone of the times in the statements, which carry none; it also decides which day is
  *   today, since a sync reads up to the day before
  */
final case class IbkrSyncConfig(
  historyStart: Option[LocalDate] = None,
  rereadDays: Int = 30,
  windowDays: Int = 30,
  statementZone: ZoneId = ZoneId.of("America/New_York")
) {
  require(rereadDays >= 0, "rereadDays must not be negative")
  require(windowDays >= 1, "windowDays must be at least 1")
}

object IbkrSyncConfig {

  /** How many calendar years back, besides the current one, IB keeps saved Flex Query statements.
    */
  val HistoryYears: Int = 4

  /** The earliest report date IB makes available on the day `today`: 1 January of the year
    * [[HistoryYears]] before the one of `today`.
    */
  def earliestHistory(today: LocalDate): LocalDate =
    LocalDate.of(today.getYear - HistoryYears, 1, 1)
}
