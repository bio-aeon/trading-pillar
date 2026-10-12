package st.btu.trading.pillar.models.domain

import java.time.ZonedDateTime

final case class TcsPortfolioOperation(
  id: PortfolioOperationId,
  extId: PortfolioOperationExtId,
  operationType: PortfolioOperationType,
  instrumentType: Option[PortfolioInstrumentType],
  status: PortfolioOperationStatus,
  figi: Option[Figi],
  currency: Currency,
  price: BigDecimal,
  quantity: Int,
  quantityRest: Int,
  amount: BigDecimal,
  protocolVersion: ProtocolVersion,
  createdAt: ZonedDateTime,
  accountId: AccountId
) {

  // Decoded view of `operationType`/`status` - see OperationType/OperationStatus.
  def operationTypeEnum: OperationType = OperationType.decode(operationType.value)
  def statusEnum: OperationStatus = OperationStatus.decode(status.value)
}
