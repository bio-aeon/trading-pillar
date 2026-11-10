package st.btu.trading.pillar.models.ibkr

import st.btu.trading.pillar.models.domain.{AccountId, OperationRef}

import java.time.LocalDate

/** One row of an Activity Flex Query statement as IB reported it, kept so that the translation into
  * operations can be done again without fetching the statement again.
  *
  * @param accountId
  *   the account the row is stored under
  * @param transactionId
  *   IB's id of the row, unique within its section
  * @param reportDate
  *   the booking date IB selects statement rows by
  * @param attributes
  *   every attribute of the row's XML element, by name and exactly as IB sent it, a placeholder for
  *   no value included
  */
final case class IbkrStatementRow(
  accountId: AccountId,
  section: IbkrSection,
  transactionId: IbkrTransactionId,
  reportDate: LocalDate,
  attributes: Map[String, String]
) {

  /** The value of attribute `name`, or `None` when IB did not send it or sent a placeholder for no
    * value: an empty string, `-`, `--` or `N/A`.
    */
  def attribute(name: String): Option[String] = IbkrAttributes.value(attributes, name)

  /** The `ref` of the row's own operation: its section and its transaction id, such as
    * `trade/123456`. It is unique within the account, since a section's transaction ids are.
    */
  def ref: OperationRef = OperationRef(
    s"${section.value}${IbkrStatementRow.RefSeparator}${transactionId.value}"
  )

  /** The `ref` of an operation of the row beyond its own, told apart by `suffix`: the row's `ref`,
    * a slash and the suffix, such as `trade/123456/commission`.
    */
  def refWith(suffix: String): OperationRef =
    OperationRef(s"${ref.value}${IbkrStatementRow.RefSeparator}$suffix")

  /** Whether `operationRef` belongs to an operation of this row: it is the row's `ref`, or the
    * row's `ref` followed by a slash and a suffix. The row's `trade/123` owns `trade/123` and
    * `trade/123/commission`, and not `trade/1234`.
    */
  def owns(operationRef: OperationRef): Boolean = {
    val own = ref.value
    val candidate = operationRef.value
    candidate == own || candidate.startsWith(own + IbkrStatementRow.RefSeparator)
  }
}

object IbkrStatementRow {
  private val RefSeparator = "/"
}
