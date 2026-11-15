package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.{InstrumentKey, OperationGroup}

/** The share of a spin-off parent's cost that moves to one of the new instruments, as the issuer
  * publishes it. A broker reports no value for a spin-off, so without an allocation the new
  * instrument takes none of the parent's cost.
  *
  * @param group
  *   the group of the spin-off's operations
  * @param instrument
  *   the new instrument the fraction is for
  * @param costFraction
  *   the fraction of the cost of each parent lot that moves to the new instrument, from 0 to 1
  */
final case class CorporateActionAllocation(
  group: OperationGroup,
  instrument: InstrumentKey,
  costFraction: BigDecimal
)
