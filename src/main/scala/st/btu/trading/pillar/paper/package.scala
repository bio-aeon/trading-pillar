package st.btu.trading.pillar

import cats.Show
import tofu.logging.Loggable

/** Paper-trading identifier type: `PaperOrderId(value)` wraps a value unchecked and `id.value`
  * unwraps it.
  */
package object paper {

  /** The id a storage gives a stored [[PaperOrder]]. */
  opaque type PaperOrderId = Long

  object PaperOrderId {
    def apply(value: Long): PaperOrderId = value
    extension (orderId: PaperOrderId) def value: Long = orderId
    implicit val show: Show[PaperOrderId] = Show.catsShowForLong
    implicit val loggable: Loggable[PaperOrderId] = Loggable.longLoggable
  }
}
