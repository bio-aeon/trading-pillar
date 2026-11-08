package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.InstrumentKey

import java.time.LocalDate

/** One open FIFO tax lot: `quantity` units of `instrument` acquired on `acquiredOn` at `unitCost`
  * per unit. For a purchased lot, `unitCost` already includes that day's pro-rated fee share.
  */
final case class Lot(
  instrument: InstrumentKey,
  quantity: BigDecimal,
  unitCost: BigDecimal,
  acquiredOn: LocalDate
)
