package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.models.domain.{AccountOperation, InstrumentKey, OperationState}

/** The position an account holds in each instrument, folded from its operations. Pure: it reads no
  * storage and no clock, so every reader of positions counts the same operations the same way.
  */
object Holdings {

  private val Zero = BigDecimal(0)

  /** The position in each instrument that `operations` leave open: the sum of the signed `quantity`
    * of the executed operations that name the instrument. An instrument whose position nets to zero
    * is left out, so every quantity in the result is nonzero, and negative for a short position.
    * Operations that are not executed, and operations that name no instrument, change nothing.
    */
  def of(operations: Iterable[AccountOperation]): Map[InstrumentKey, BigDecimal] =
    after(Map.empty, operations)

  /** `held` once the executed operations in `operations` are applied on top of it, for a caller
    * that walks an account's operations in steps. `held` is a result of [[of]] or of an earlier
    * call, so it holds nonzero quantities only, and so does the result.
    */
  def after(
    held: Map[InstrumentKey, BigDecimal],
    operations: Iterable[AccountOperation]
  ): Map[InstrumentKey, BigDecimal] =
    operations.iterator
      .filter(_.state == OperationState.Executed)
      .foldLeft(held) { (positions, operation) =>
        operation.instrument.fold(positions) { instrument =>
          val quantity = positions.getOrElse(instrument, Zero) + operation.quantity
          if (quantity.signum == 0) positions - instrument
          else positions.updated(instrument, quantity)
        }
      }
}
