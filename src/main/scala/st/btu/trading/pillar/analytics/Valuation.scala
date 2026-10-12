package st.btu.trading.pillar.analytics

import java.time.LocalDate

/** A single portfolio valuation observation, consumed by `Performance.timeWeighted`/
  * `moneyWeighted`. A series of `Valuation`s is expected to already be in chronological order;
  * nothing in this package re-sorts it.
  *
  * @param externalFlow
  *   the net deposit (positive) or withdrawal (negative) booked on `date`, assumed to occur at the
  *   start of the day, before that day's return is realized
  */
final case class Valuation(date: LocalDate, value: BigDecimal, externalFlow: BigDecimal)
