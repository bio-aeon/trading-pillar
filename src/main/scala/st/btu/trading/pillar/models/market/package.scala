package st.btu.trading.pillar.models

import cats.Show
import tofu.logging.Loggable

/** Market data identifier types: `X(value)` wraps a value unchecked and `x.value` unwraps it. */
package object market {

  // Named Ticker, not Symbol, to avoid shadowing scala.Symbol
  /** A trading symbol, e.g. `"AAPL"`. */
  opaque type Ticker = String

  object Ticker {
    def apply(value: String): Ticker = value
    extension (ticker: Ticker) def value: String = ticker
    implicit val show: Show[Ticker] = Show.catsShowForString
    implicit val loggable: Loggable[Ticker] = Loggable.stringValue
  }

  /** A broker's code for a trading board, e.g. `"TQBR"`: the venue a ticker is quoted on. */
  opaque type ClassCode = String

  object ClassCode {
    def apply(value: String): ClassCode = value
    extension (classCode: ClassCode) def value: String = classCode
    implicit val show: Show[ClassCode] = Show.catsShowForString
    implicit val loggable: Loggable[ClassCode] = Loggable.stringValue
  }
}
