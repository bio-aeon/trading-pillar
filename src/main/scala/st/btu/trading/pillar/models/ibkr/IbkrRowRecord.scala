package st.btu.trading.pillar.models.ibkr

import st.btu.trading.pillar.models.domain.AccountOperation

/** A statement row paired with its translations into `AccountOperation`s. The two are recorded
  * together.
  */
final case class IbkrRowRecord(row: IbkrStatementRow, translations: List[AccountOperation])
