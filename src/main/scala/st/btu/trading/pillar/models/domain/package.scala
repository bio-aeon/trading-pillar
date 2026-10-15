package st.btu.trading.pillar.models

import cats.Show
import io.estatico.newtype.macros.newtype
import tofu.logging.Loggable

import java.util.Locale

package object domain {

  @newtype final case class AccountId(value: Int)

  object AccountId {
    implicit val loggable: Loggable[AccountId] = deriving
  }

  @newtype final case class PortfolioOperationId(value: Long)
  @newtype final case class PortfolioOperationExtId(value: String)

  object PortfolioOperationExtId {
    implicit val show: Show[PortfolioOperationExtId] = deriving
  }

  @newtype final case class PortfolioOperationType(value: String)

  object PortfolioOperationType {
    implicit val show: Show[PortfolioOperationType] = deriving
  }

  @newtype final case class PortfolioInstrumentType(value: String)
  @newtype final case class PortfolioOperationStatus(value: String)
  @newtype final case class Figi(value: String)

  /** A currency code: an ISO 4217 code such as `RUB`, or a minor unit marked by case such as `GBp`,
    * pence.
    *
    * The only constructor, `Currency(code)`, normalises its argument, so two spellings of one
    * currency are equal values and codes compare exactly by construction. Whitespace around the
    * code is trimmed. A code with no upper-case letter is upper-cased, which is how the Tinkoff API
    * spells ISO 4217 codes (`rub`). Any other code is kept as given, because Yahoo marks a minor
    * unit by case: `GBp` is pence and is not folded into `GBP`, pounds. `value` is the normalised
    * code, the form to print and to persist.
    */
  @newtype class Currency(val value: String)

  object Currency {
    import io.estatico.newtype.ops.*

    /** The currency written `code`, normalised as described on [[Currency]]. */
    def apply(code: String): Currency = {
      val trimmed = code.trim
      val normalised = if (trimmed.exists(_.isUpper)) trimmed else trimmed.toUpperCase(Locale.ROOT)
      normalised.coerce[Currency]
    }
  }

  @newtype final case class ProtocolVersion(value: String)
}
