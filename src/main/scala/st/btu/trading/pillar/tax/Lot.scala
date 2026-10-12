package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.Figi

import java.time.LocalDate

/** One open FIFO tax lot: `quantity` shares of `figi` acquired on `acquiredOn` at `unitCost` per
  * share. For a purchased lot, `unitCost` already includes that day's pro-rated commission share.
  */
final case class Lot(figi: Figi, quantity: Long, unitCost: BigDecimal, acquiredOn: LocalDate)
