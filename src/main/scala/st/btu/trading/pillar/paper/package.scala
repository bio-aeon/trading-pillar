package st.btu.trading.pillar

import cats.Show
import io.estatico.newtype.macros.newtype
import tofu.logging.Loggable

package object paper {

  @newtype final case class PaperOrderId(value: Long)

  object PaperOrderId {
    implicit val show: Show[PaperOrderId] = deriving
    implicit val loggable: Loggable[PaperOrderId] = deriving
  }
}
