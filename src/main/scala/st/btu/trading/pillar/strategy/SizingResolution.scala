package st.btu.trading.pillar.strategy

import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import scala.math.BigDecimal.RoundingMode

// Pure fill-price and quantity-sizing math shared by strategy.backtest.BacktestEngine and
// paper.PaperBrokerImpl, so a live paper fill and a backtest fill resolve a StrategySignal's
// Sizing into a concrete whole-share quantity (and a slippage-adjusted price) the exact same way.
object SizingResolution {

  // 1 basis point = 1/10000, so a slippageBps of 100 is 1%.
  private val BasisPointsPerUnit = 10000

  def buyFillPrice(referencePrice: BigDecimal, slippageBps: Int): BigDecimal =
    referencePrice * (1 + divide(BigDecimal(slippageBps), BigDecimal(BasisPointsPerUnit)))

  def sellFillPrice(referencePrice: BigDecimal, slippageBps: Int): BigDecimal =
    referencePrice * (1 - divide(BigDecimal(slippageBps), BigDecimal(BasisPointsPerUnit)))

  def maxAffordableQty(cash: BigDecimal, price: BigDecimal, commissionRate: BigDecimal): Long = {
    val perShareCost = price * (1 + commissionRate)
    if (price <= 0 || perShareCost <= 0) 0L
    else floorToLong(divide(cash, perShareCost))
  }

  private def notionalToQty(notional: BigDecimal, price: BigDecimal): Long =
    if (price <= 0) 0L else floorToLong(divide(notional, price))

  private def floorToLong(value: BigDecimal): Long =
    value.setScale(0, RoundingMode.FLOOR).toLongExact.max(0L)

  def resolveBuyQuantity(
    sizing: Sizing,
    cash: BigDecimal,
    equity: BigDecimal,
    price: BigDecimal,
    commissionRate: BigDecimal
  ): Long = {
    val maxQty = maxAffordableQty(cash, price, commissionRate)
    val desired = sizing match {
      case Sizing.AllIn => maxQty
      case Sizing.Quantity(n) => n.max(0L)
      case Sizing.FractionOfEquity(fraction) => notionalToQty(equity * fraction, price)
      case Sizing.Notional(amount) => notionalToQty(amount, price)
      case Sizing.ClosePosition => 0L
    }
    desired.min(maxQty).max(0L)
  }

  def resolveSellQuantity(
    sizing: Sizing,
    ownedQuantity: Long,
    equity: BigDecimal,
    price: BigDecimal
  ): Long = {
    val desired = sizing match {
      case Sizing.AllIn => ownedQuantity
      case Sizing.ClosePosition => ownedQuantity
      case Sizing.Quantity(n) => n.max(0L)
      case Sizing.FractionOfEquity(fraction) => notionalToQty(equity * fraction, price)
      case Sizing.Notional(amount) => notionalToQty(amount, price)
    }
    desired.min(ownedQuantity).max(0L)
  }
}
