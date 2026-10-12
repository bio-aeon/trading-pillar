package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.Figi

/** The result of [[TaxLots.fifo]] over one account's executed operations.
  *
  * @param open
  *   lots not yet, or not fully, consumed by a sell, oldest first, keyed by figi; a figi with no
  *   remaining open quantity has no entry at all
  * @param realized
  *   exactly one entry per sell (or disposal) operation, whether or not it was fully covered by
  *   open lots, sorted by (sellDate, figi)
  * @param unmatched
  *   per figi, the quantity sold that exceeded the lots available at the time; absent for a figi
  *   that was never oversold
  */
final case class LotLedger(
  open: Map[Figi, List[Lot]],
  realized: List[RealizedGain],
  unmatched: Map[Figi, Long]
)

object LotLedger {

  /** A ledger with no open lots, realized gains or unmatched sales. */
  val empty: LotLedger = LotLedger(Map.empty, Nil, Map.empty)
}
