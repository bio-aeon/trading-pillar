package st.btu.trading.pillar.models.domain

import enumeratum.values.*

/** Every Tinkoff `OPERATION_TYPE_*` name trading-pillar itself inspects (tax lots/report, portfolio
  * valuation). An open set: a raw value that doesn't match any named case decodes to `Unknown(raw)`
  * via [[OperationType.decode]] rather than being rejected, since an operation type this library
  * doesn't yet recognize must still round-trip unchanged.
  */
sealed abstract class OperationType(val value: String) extends StringEnumEntry {

  def isBuy: Boolean = OperationType.BuyTypes.contains(this)
  def isSell: Boolean = OperationType.SellTypes.contains(this)
  def isExternalFlow: Boolean = this == OperationType.Input || this == OperationType.Output
  def isDividendOrCoupon: Boolean = this == OperationType.Dividend || this == OperationType.Coupon
  def isWithheldTax: Boolean = OperationType.WithheldTaxTypes.contains(this)
}

object OperationType extends StringEnum[OperationType] {
  case object Buy extends OperationType("OPERATION_TYPE_BUY")
  case object BuyCard extends OperationType("OPERATION_TYPE_BUY_CARD")
  case object BuyMargin extends OperationType("OPERATION_TYPE_BUY_MARGIN")
  case object DeliveryBuy extends OperationType("OPERATION_TYPE_DELIVERY_BUY")
  case object Sell extends OperationType("OPERATION_TYPE_SELL")
  case object SellCard extends OperationType("OPERATION_TYPE_SELL_CARD")
  case object SellMargin extends OperationType("OPERATION_TYPE_SELL_MARGIN")
  case object DeliverySell extends OperationType("OPERATION_TYPE_DELIVERY_SELL")
  case object BondRepaymentFull extends OperationType("OPERATION_TYPE_BOND_REPAYMENT_FULL")
  case object InputSecurities extends OperationType("OPERATION_TYPE_INPUT_SECURITIES")
  case object OutputSecurities extends OperationType("OPERATION_TYPE_OUTPUT_SECURITIES")
  case object Input extends OperationType("OPERATION_TYPE_INPUT")
  case object Output extends OperationType("OPERATION_TYPE_OUTPUT")
  case object Dividend extends OperationType("OPERATION_TYPE_DIVIDEND")
  case object Coupon extends OperationType("OPERATION_TYPE_COUPON")
  case object BrokerFee extends OperationType("OPERATION_TYPE_BROKER_FEE")
  case object BondTax extends OperationType("OPERATION_TYPE_BOND_TAX")
  case object DividendTax extends OperationType("OPERATION_TYPE_DIVIDEND_TAX")
  case object Tax extends OperationType("OPERATION_TYPE_TAX")
  case object TaxCorrection extends OperationType("OPERATION_TYPE_TAX_CORRECTION")
  case object BenefitTax extends OperationType("OPERATION_TYPE_BENEFIT_TAX")
  case object TaxProgressive extends OperationType("OPERATION_TYPE_TAX_PROGRESSIVE")
  case object BondTaxProgressive extends OperationType("OPERATION_TYPE_BOND_TAX_PROGRESSIVE")
  case object DividendTaxProgressive
      extends OperationType("OPERATION_TYPE_DIVIDEND_TAX_PROGRESSIVE")
  case object BenefitTaxProgressive extends OperationType("OPERATION_TYPE_BENEFIT_TAX_PROGRESSIVE")
  case object TaxCorrectionProgressive
      extends OperationType("OPERATION_TYPE_TAX_CORRECTION_PROGRESSIVE")
  case object TaxRepo extends OperationType("OPERATION_TYPE_TAX_REPO")
  case object TaxRepoProgressive extends OperationType("OPERATION_TYPE_TAX_REPO_PROGRESSIVE")
  case object TaxRepoHold extends OperationType("OPERATION_TYPE_TAX_REPO_HOLD")
  case object TaxRepoRefund extends OperationType("OPERATION_TYPE_TAX_REPO_REFUND")
  case object TaxRepoHoldProgressive
      extends OperationType("OPERATION_TYPE_TAX_REPO_HOLD_PROGRESSIVE")
  case object TaxRepoRefundProgressive
      extends OperationType("OPERATION_TYPE_TAX_REPO_REFUND_PROGRESSIVE")

  // Not part of `values` (findValues would reject a non-singleton case as a value member, and
  // this one is intentionally outside the closed set it enumerates).
  /** Carries any raw value not among the named cases above. */
  final case class Unknown(raw: String) extends OperationType(raw)

  // Hand-written rather than `findValues`: the macro only accepts singleton cases, and `Unknown`
  // above is a sealed sibling it would otherwise trip over.
  /** Every named case, in declaration order. */
  val values: IndexedSeq[OperationType] = IndexedSeq(
    Buy,
    BuyCard,
    BuyMargin,
    DeliveryBuy,
    Sell,
    SellCard,
    SellMargin,
    DeliverySell,
    BondRepaymentFull,
    InputSecurities,
    OutputSecurities,
    Input,
    Output,
    Dividend,
    Coupon,
    BrokerFee,
    BondTax,
    DividendTax,
    Tax,
    TaxCorrection,
    BenefitTax,
    TaxProgressive,
    BondTaxProgressive,
    DividendTaxProgressive,
    BenefitTaxProgressive,
    TaxCorrectionProgressive,
    TaxRepo,
    TaxRepoProgressive,
    TaxRepoHold,
    TaxRepoRefund,
    TaxRepoHoldProgressive,
    TaxRepoRefundProgressive
  )

  /** Decodes a raw Tinkoff operation-type string, falling back to [[Unknown]] rather than throwing.
    */
  def decode(raw: String): OperationType = withValueOpt(raw).getOrElse(Unknown(raw))

  private val BuyTypes: Set[OperationType] = Set(Buy, BuyCard, BuyMargin, DeliveryBuy)

  private val SellTypes: Set[OperationType] =
    Set(Sell, SellCard, SellMargin, DeliverySell, BondRepaymentFull)

  // BOND_TAX, DIVIDEND_TAX, TAX, TAX_CORRECTION, BENEFIT_TAX, their _PROGRESSIVE variants, and
  // every TAX_REPO* variant - the broker-withheld-tax family TaxReport treats as "already paid".
  // Deliberately excludes TAX_CORRECTION_COUPON (matches neither this family nor its own name).
  private val WithheldTaxTypes: Set[OperationType] = Set(
    BondTax,
    DividendTax,
    Tax,
    TaxCorrection,
    BenefitTax,
    TaxProgressive,
    BondTaxProgressive,
    DividendTaxProgressive,
    BenefitTaxProgressive,
    TaxCorrectionProgressive,
    TaxRepo,
    TaxRepoProgressive,
    TaxRepoHold,
    TaxRepoRefund,
    TaxRepoHoldProgressive,
    TaxRepoRefundProgressive
  )
}

/** The Tinkoff `OPERATION_STATE_*` names trading-pillar inspects; an open set with the same
  * `Unknown(raw)` fallback as [[OperationType]].
  */
sealed abstract class OperationStatus(val value: String) extends StringEnumEntry

object OperationStatus extends StringEnum[OperationStatus] {
  case object Executed extends OperationStatus("OPERATION_STATE_EXECUTED")

  /** Carries any raw value not among the named cases above. */
  final case class Unknown(raw: String) extends OperationStatus(raw)

  /** Every named case, in declaration order. */
  val values: IndexedSeq[OperationStatus] = IndexedSeq(Executed)

  /** Decodes a raw Tinkoff operation-state string, falling back to [[Unknown]] rather than
    * throwing.
    */
  def decode(raw: String): OperationStatus = withValueOpt(raw).getOrElse(Unknown(raw))
}
