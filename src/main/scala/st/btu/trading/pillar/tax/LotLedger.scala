package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.InstrumentKey

/** The result of [[TaxLots.fifo]] over one account's executed operations.
  *
  * @param open
  *   lots not yet, or not fully, consumed by a sell, oldest first, keyed by instrument; an
  *   instrument with no remaining open quantity has no entry at all
  * @param realized
  *   exactly one entry per sell operation, whether or not it was fully covered by open lots, sorted
  *   by (sellDate, instrument)
  * @param unmatched
  *   per instrument, the quantity sold that exceeded the lots available at the time; absent for an
  *   instrument that was never oversold
  */
final case class LotLedger(
  open: Map[InstrumentKey, List[Lot]],
  realized: List[RealizedGain],
  unmatched: Map[InstrumentKey, BigDecimal]
)

object LotLedger {

  /** A ledger with no open lots, realized gains or unmatched sales. */
  val empty: LotLedger = LotLedger(Map.empty, Nil, Map.empty)
}
