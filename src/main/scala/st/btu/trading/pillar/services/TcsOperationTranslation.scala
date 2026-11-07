package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.domain.*

/** Translates Tinkoff operations into `AccountOperation`s. Pure: the translation of an operation
  * depends on nothing but the operation.
  */
object TcsOperationTranslation {

  /** The translation of `operation`.
    *
    * The kind comes from the operation's type, and is `OperationKind.Other` for a type this library
    * does not name. The state is executed or canceled as Tinkoff reports it and pending otherwise.
    * The quantity is the executed units, `quantity` less `quantityRest`, signed by the kind. The
    * `ref` is the operation's id and type joined with a slash, the key a stored Tinkoff operation
    * is identified by.
    */
  def translate(operation: TcsPortfolioOperation): AccountOperation = {
    val kind = kindOf(operation.operationTypeEnum)
    AccountOperation(
      accountId = operation.accountId,
      ref = OperationRef(s"${operation.extId.value}/${operation.operationType.value}"),
      kind = kind,
      state = stateOf(operation.statusEnum),
      occurredAt = operation.createdAt.toInstant,
      instrument =
        operation.figi.map(figi => InstrumentKey(Broker.Tinkoff, BrokerInstrumentId(figi.value))),
      quantity = signedQuantity(kind, operation.quantity - operation.quantityRest),
      price = Some(operation.price).filter(_.signum != 0),
      amount = operation.amount,
      currency = operation.currency,
      brokerType = BrokerOperationType(operation.operationType.value)
    )
  }

  /** `operation` paired with its translation. */
  def recordOf(operation: TcsPortfolioOperation): TcsOperationRecord =
    TcsOperationRecord(operation, translate(operation))

  private def kindOf(operationType: OperationType): OperationKind = {
    import OperationType.*
    operationType match {
      case Buy | BuyCard | BuyMargin | DeliveryBuy =>
        OperationKind.Buy
      case Sell | SellCard | SellMargin | DeliverySell | BondRepaymentFull =>
        OperationKind.Sell
      case InputSecurities =>
        OperationKind.SecurityIn
      case OutputSecurities =>
        OperationKind.SecurityOut
      case Input | InputSwift | InputAcquiring | InpMulti =>
        OperationKind.Deposit
      case Output | OutputSwift | OutputAcquiring | OutMulti =>
        OperationKind.Withdrawal
      case Dividend | DivExt =>
        OperationKind.Dividend
      case Coupon =>
        OperationKind.Coupon
      case Overnight | OverIncome =>
        OperationKind.Interest
      case BrokerFee | ServiceFee | MarginFee | SuccessFee | TrackMfee | TrackPfee | CashFee |
          OutFee | AdviceFee | OtherFee | OutStampDuty | OutputPenalty | OverCom | Funding =>
        OperationKind.Fee
      case Tax | TaxProgressive | BondTax | BondTaxProgressive | DividendTax |
          DividendTaxProgressive | BenefitTax | BenefitTaxProgressive | TaxCorrection |
          TaxCorrectionProgressive | TaxCorrectionCoupon | TaxRepo | TaxRepoProgressive |
          TaxRepoHold | TaxRepoHoldProgressive | TaxRepoRefund | TaxRepoRefundProgressive =>
        OperationKind.Tax
      case Unknown(_) =>
        OperationKind.Other
    }
  }

  private def stateOf(status: OperationStatus): OperationState =
    status match {
      case OperationStatus.Executed => OperationState.Executed
      case OperationStatus.Canceled => OperationState.Canceled
      case OperationStatus.Progress | OperationStatus.Unknown(_) => OperationState.Pending
    }

  private def signedQuantity(kind: OperationKind, executedUnits: Int): BigDecimal =
    kind match {
      case OperationKind.Buy | OperationKind.SecurityIn => BigDecimal(executedUnits)
      case OperationKind.Sell | OperationKind.SecurityOut => BigDecimal(-executedUnits)
      case OperationKind.Deposit | OperationKind.Withdrawal | OperationKind.Dividend |
          OperationKind.Coupon | OperationKind.Interest | OperationKind.Fee | OperationKind.Tax |
          OperationKind.Other =>
        BigDecimal(0)
    }
}
