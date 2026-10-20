package st.btu.trading.pillar.analytics

/** Portfolio identifier type: `AllocationTargetId(value)` wraps a value unchecked and `id.value`
  * unwraps it.
  */
package object portfolio {

  /** The id a storage gives a stored [[AllocationTarget]]. */
  opaque type AllocationTargetId = Long

  object AllocationTargetId {
    def apply(value: Long): AllocationTargetId = value
    extension (targetId: AllocationTargetId) def value: Long = targetId
  }
}
