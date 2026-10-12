package st.btu.trading.pillar.analytics

import io.estatico.newtype.macros.newtype

package object portfolio {

  @newtype final case class AllocationTargetId(value: Long)
}
