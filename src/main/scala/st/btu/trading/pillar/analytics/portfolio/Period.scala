package st.btu.trading.pillar.analytics.portfolio

import enumeratum.values.{StringEnum, StringEnumEntry}

import java.time.LocalDate

/** The span of history a performance report covers, counted back from the date it ends on.
  *
  * @param value
  *   the token that names the period: `1m`, `3m`, `6m`, `1y` or `all`, which is also what
  *   `/performance` accepts for it
  */
sealed abstract class Period(val value: String) extends StringEnumEntry {

  /** The first date of the period that ends on `end`.
    *
    * Months and years count on the calendar, so a day the earlier month lacks becomes its last day:
    * one month ending 2024-03-31 starts 2024-02-29.
    */
  def from(end: LocalDate): LocalDate
}

object Period extends StringEnum[Period] {

  /** How many years [[All]] counts back from its end date.
    *
    * `PortfolioValuationStorage` has no "since inception" query, so the full series is read as this
    * many years back: more than any real account history, and an ordinary date that is safe to pass
    * to a JDBC driver.
    */
  val InceptionLookbackYears = 50

  // `from` is a method of each case, not a constructor argument: the compiler hoists such an
  // argument into this object, so the first use of a case object would initialise this object
  // while that case object is still null, and `values` would keep the null.

  /** One month back from the end date. */
  case object OneMonth extends Period("1m") {
    def from(end: LocalDate): LocalDate = end.minusMonths(1)
  }

  /** Three months back from the end date. */
  case object ThreeMonths extends Period("3m") {
    def from(end: LocalDate): LocalDate = end.minusMonths(3)
  }

  /** Six months back from the end date. */
  case object SixMonths extends Period("6m") {
    def from(end: LocalDate): LocalDate = end.minusMonths(6)
  }

  /** One year back from the end date. */
  case object OneYear extends Period("1y") {
    def from(end: LocalDate): LocalDate = end.minusYears(1)
  }

  /** The whole stored series, taken as [[InceptionLookbackYears]] years back. */
  case object All extends Period("all") {
    def from(end: LocalDate): LocalDate = end.minusYears(InceptionLookbackYears.toLong)
  }

  val values = findValues

  /** The period whose token is `s`, ignoring case, or `None` when no period has that token. */
  def parse(s: String): Option[Period] =
    // Every value is lowercase, so an exact lookup of the lowercased token is case-insensitive.
    withValueOpt(s.toLowerCase)
}
