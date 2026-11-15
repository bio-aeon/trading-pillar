package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.InstrumentKey

import java.time.LocalDate

/** One open FIFO tax lot: `quantity` units of `instrument` acquired on `acquiredOn` at `unitCost`
  * per unit. For a purchased lot, `unitCost` already includes that day's pro-rated fee share. A lot
  * that a corporate action carried over from another keeps that lot's acquisition date, and the
  * cost the action left it.
  */
final case class Lot(
  instrument: InstrumentKey,
  quantity: BigDecimal,
  unitCost: BigDecimal,
  acquiredOn: LocalDate
)
