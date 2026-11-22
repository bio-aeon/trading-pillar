package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.{Currency, InstrumentKey}

import java.time.LocalDate

/** One open FIFO tax lot: `quantity` units of `instrument` acquired on `acquiredOn`, each costing
  * `unitCost`.
  *
  * @param unitCost
  *   the cost of one unit in each currency it was paid in, with no zero amount, so a lot that cost
  *   nothing has none. A lot bought for 20000 euros a unit has `Map(EUR -> 20000)`, and it stays in
  *   euros whatever currency it is sold in. For a purchased lot it already includes that day's
  *   pro-rated fee share, in the currency of the fee. A lot that a corporate action carried over
  *   from another keeps that lot's acquisition date and the cost the action left it, in the
  *   currencies of that cost, and the cash a conversion charged adds to it in the currency of that
  *   cash
  */
final case class Lot(
  instrument: InstrumentKey,
  quantity: BigDecimal,
  unitCost: Map[Currency, BigDecimal],
  acquiredOn: LocalDate
)
