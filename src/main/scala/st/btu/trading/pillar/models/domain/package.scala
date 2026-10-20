package st.btu.trading.pillar.models

import cats.Show
import tofu.logging.Loggable

import java.util.Locale

/** Identifier and code types of the domain models: `X(value)` wraps a value unchecked and `x.value`
  * unwraps it; `Currency(code)` normalises the code instead.
  */
package object domain {

  /** The id of an account: the owner of the watchlists, alert rules, operations and valuations kept
    * for it.
    */
  opaque type AccountId = Int

  object AccountId {
    def apply(value: Int): AccountId = value
    extension (accountId: AccountId) def value: Int = accountId
    implicit val loggable: Loggable[AccountId] = Loggable.intLoggable
  }

  /** The id a storage gives a stored [[TcsPortfolioOperation]]; `0` until the operation is stored.
    */
  opaque type PortfolioOperationId = Long

  object PortfolioOperationId {
    def apply(value: Long): PortfolioOperationId = value
    extension (operationId: PortfolioOperationId) def value: Long = operationId
  }

  /** Tinkoff's own id of a portfolio operation, which with the operation's type identifies it
    * across syncs.
    */
  opaque type PortfolioOperationExtId = String

  object PortfolioOperationExtId {
    def apply(value: String): PortfolioOperationExtId = value
    extension (extId: PortfolioOperationExtId) def value: String = extId
    implicit val show: Show[PortfolioOperationExtId] = Show.catsShowForString
  }

  /** Tinkoff's name for the kind of a portfolio operation, such as `OPERATION_TYPE_BUY`, kept as
    * received; [[OperationType]] decodes it.
    */
  opaque type PortfolioOperationType = String

  object PortfolioOperationType {
    def apply(value: String): PortfolioOperationType = value
    extension (operationType: PortfolioOperationType) def value: String = operationType
    implicit val show: Show[PortfolioOperationType] = Show.catsShowForString
  }

  /** Tinkoff's name for the kind of instrument an operation concerns, such as `share`, kept as
    * received.
    */
  opaque type PortfolioInstrumentType = String

  object PortfolioInstrumentType {
    def apply(value: String): PortfolioInstrumentType = value
    extension (instrumentType: PortfolioInstrumentType) def value: String = instrumentType
  }

  /** Tinkoff's state of a portfolio operation, such as `OPERATION_STATE_EXECUTED`, kept as
    * received; [[OperationStatus]] decodes it.
    */
  opaque type PortfolioOperationStatus = String

  object PortfolioOperationStatus {
    def apply(value: String): PortfolioOperationStatus = value
    extension (status: PortfolioOperationStatus) def value: String = status
  }

  /** A Financial Instrument Global Identifier, the id Tinkoff knows an instrument by, such as
    * `BBG004730N88`.
    */
  opaque type Figi = String

  object Figi {
    def apply(value: String): Figi = value
    extension (figi: Figi) def value: String = figi
  }

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
  opaque type Currency = String

  object Currency {

    /** The currency written `code`, normalised as described on [[Currency]]. */
    def apply(code: String): Currency = {
      val trimmed = code.trim
      if (trimmed.exists(_.isUpper)) trimmed else trimmed.toUpperCase(Locale.ROOT)
    }

    extension (currency: Currency) def value: String = currency
  }

  /** The version of the Tinkoff API contract a portfolio operation was read with. */
  opaque type ProtocolVersion = String

  object ProtocolVersion {
    def apply(value: String): ProtocolVersion = value
    extension (version: ProtocolVersion) def value: String = version
  }
}
