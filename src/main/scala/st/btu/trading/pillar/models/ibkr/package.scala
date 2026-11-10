package st.btu.trading.pillar.models

import cats.Show
import tofu.logging.Loggable

/** Interactive Brokers identifier and credential types: `X(value)` wraps a value unchecked and
  * `x.value` unwraps it.
  */
package object ibkr {

  /** The token that authorises reads through the Flex Web Service. It is a secret: its `Show` and
    * `Loggable` instances print a mask, so logging a token does not reveal it. Take `value` only to
    * build a request.
    */
  opaque type FlexToken = String

  object FlexToken {
    private val Mask = "FlexToken(***)"

    def apply(value: String): FlexToken = value
    extension (token: FlexToken) def value: String = token
    implicit val show: Show[FlexToken] = Show.show(_ => Mask)
    implicit val loggable: Loggable[FlexToken] = Loggable.stringValue.contramap(_ => Mask)
  }

  /** The id of an Activity Flex Query saved in the Client Portal, which the Flex Web Service runs
    * for a statement.
    */
  opaque type FlexQueryId = String

  object FlexQueryId {
    def apply(value: String): FlexQueryId = value
    extension (queryId: FlexQueryId) def value: String = queryId
    implicit val show: Show[FlexQueryId] = Show.catsShowForString
    implicit val loggable: Loggable[FlexQueryId] = Loggable.stringValue
  }

  /** An account at Interactive Brokers as IB numbers it, such as `U1234567`. */
  opaque type IbkrAccountId = String

  object IbkrAccountId {
    def apply(value: String): IbkrAccountId = value
    extension (accountId: IbkrAccountId) def value: String = accountId
    implicit val show: Show[IbkrAccountId] = Show.catsShowForString
    implicit val loggable: Loggable[IbkrAccountId] = Loggable.stringValue
  }

  /** IB's id of one statement row, its `transactionID`, which identifies the row within an account
    * across statements.
    */
  opaque type IbkrTransactionId = String

  object IbkrTransactionId {
    def apply(value: String): IbkrTransactionId = value
    extension (transactionId: IbkrTransactionId) def value: String = transactionId
    implicit val show: Show[IbkrTransactionId] = Show.catsShowForString
    implicit val loggable: Loggable[IbkrTransactionId] = Loggable.stringValue
  }
}
