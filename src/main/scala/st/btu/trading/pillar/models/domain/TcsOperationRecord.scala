package st.btu.trading.pillar.models.domain

/** A Tinkoff operation as received, paired with its translation into an [[AccountOperation]]. The
  * two are recorded together.
  */
final case class TcsOperationRecord(operation: TcsPortfolioOperation, translation: AccountOperation)
