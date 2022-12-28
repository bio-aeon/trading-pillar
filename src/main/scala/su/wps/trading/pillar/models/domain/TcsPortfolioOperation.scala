package su.wps.trading.pillar.models.domain

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
)
