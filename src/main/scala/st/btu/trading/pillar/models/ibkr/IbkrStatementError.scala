package st.btu.trading.pillar.models.ibkr

/** Why a statement, or a row of one, cannot be used. */
sealed abstract class IbkrStatementError(message: String) extends Exception(message)

object IbkrStatementError {

  /** The text is not an Activity Flex Query response in XML, or a statement in it lacks what every
    * statement carries.
    */
  final case class Malformed(detail: String)
      extends IbkrStatementError(s"Malformed Flex statement: $detail")

  /** A row lacks an attribute the sync needs, which is usually a field left out of the Flex Query.
    * The row has no id when the id is what is missing.
    */
  final case class MissingAttribute(
    section: IbkrSection,
    transactionId: Option[IbkrTransactionId],
    attribute: String
  ) extends IbkrStatementError(
        s"The ${section.value} row${rowId(transactionId)} has no $attribute; " +
          "add the field to the Flex Query"
      )

  /** A row carries an attribute whose value is not of the expected form. */
  final case class InvalidAttribute(
    section: IbkrSection,
    transactionId: Option[IbkrTransactionId],
    attribute: String,
    value: String
  ) extends IbkrStatementError(
        s"The ${section.value} row${rowId(transactionId)} has an invalid $attribute: '$value'"
      )

  /** The response has no statement for the configured IB account. The message names no IB account,
    * since the message of a sync that fails reaches the logs and the system alerts.
    *
    * @param found
    *   the IB accounts the response has statements for
    */
  final case class AccountNotInStatement(expected: IbkrAccountId, found: List[IbkrAccountId])
      extends IbkrStatementError(
        "The Flex response has no statement for the configured IB account; " +
          "check its account id and the accounts the Flex Query covers"
      )

  private def rowId(transactionId: Option[IbkrTransactionId]): String =
    transactionId.fold("")(id => s" ${id.value}")
}
