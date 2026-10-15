package st.btu.trading.pillar.models

import cats.Show
import io.estatico.newtype.macros.newtype
import tofu.logging.Loggable

package object market {

  // Named Ticker, not Symbol, to avoid shadowing scala.Symbol
  /** A trading symbol, e.g. `"AAPL"`. */
  @newtype final case class Ticker(value: String)

  object Ticker {
    implicit val show: Show[Ticker] = deriving
    implicit val loggable: Loggable[Ticker] = deriving
  }

  /** A broker's code for a trading board, e.g. `"TQBR"`: the venue a ticker is quoted on. */
  @newtype final case class ClassCode(value: String)

  object ClassCode {
    implicit val show: Show[ClassCode] = deriving
    implicit val loggable: Loggable[ClassCode] = deriving
  }
}
