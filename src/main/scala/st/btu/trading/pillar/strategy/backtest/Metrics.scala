package st.btu.trading.pillar.strategy.backtest

import st.btu.trading.pillar.analytics.{Performance, Returns, Risk}
import st.btu.trading.pillar.strategy.Side
import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.temporal.ChronoUnit

// Pure metrics computation over a finished backtest's equity curve and trade log. Delegates the
// definitions it shares with the analytics library (total return, annualized return, max
// drawdown, Sharpe) to st.btu.trading.pillar.analytics - see docs/analytics.md.
object Metrics {

  private[pillar] def compute(
    initialCash: BigDecimal,
    equityCurve: List[EquityPoint],
    trades: List[Trade],
    firstOpen: BigDecimal,
    lastClose: BigDecimal
  ): BacktestMetrics = {
    val finalEquity = equityCurve.lastOption.map(_.equity).getOrElse(initialCash)
    val totalReturn = Performance.totalReturn(initialCash, finalEquity)

    val calendarDays = (equityCurve.headOption, equityCurve.lastOption) match {
      case (Some(first), Some(last)) => ChronoUnit.DAYS.between(first.date, last.date)
      case _ => 0L
    }
    val annualizedReturn = Performance.annualized(totalReturn, calendarDays)

    val roundTripPnls = roundTrips(trades)
    val winRate =
      if (roundTripPnls.isEmpty) None
      else Some(divide(BigDecimal(roundTripPnls.count(_ > 0)), BigDecimal(roundTripPnls.size)))

    BacktestMetrics(
      totalReturn = totalReturn,
      annualizedReturn = annualizedReturn,
      maxDrawdown = Risk.maxDrawdown(equityCurve.map(_.equity).toVector),
      sharpeAnnualized = sharpeAnnualized(equityCurve),
      winRate = winRate,
      tradeCount = trades.size,
      roundTrips = roundTripPnls.size,
      finalEquity = finalEquity,
      buyAndHoldReturn = Performance.totalReturn(firstOpen, lastClose)
    )
  }

  private def sharpeAnnualized(equityCurve: List[EquityPoint]): BigDecimal =
    Risk.sharpe(Returns.simple(equityCurve.map(_.equity).toVector))

  // PnL per round trip: a round trip runs from a flat position opening (buy) to it returning to
  // flat (quantity == 0). PnL = sell proceeds - cost of the shares sold - commissions; since a
  // round trip always closes back to 0, "cost of the shares sold" is just the round trip's total
  // buy notional.
  // Package-visible so BacktestEngine.runPortfolio can isolate round trips per ticker (never
  // netting one ticker's buys against another's sells) and combine the resulting PnLs itself.
  private[backtest] def roundTrips(trades: List[Trade]): List[BigDecimal] = {
    final case class Acc(
      quantity: Long,
      buyNotional: BigDecimal,
      sellNotional: BigDecimal,
      commissions: BigDecimal,
      completed: List[BigDecimal]
    )
    val zero = Acc(0L, 0, 0, 0, Nil)

    val result = trades.foldLeft(zero) { (acc, trade) =>
      val notional = BigDecimal(trade.quantity) * trade.price
      val (quantity, buyNotional, sellNotional) = trade.side match {
        case Side.Buy =>
          (acc.quantity + trade.quantity, acc.buyNotional + notional, acc.sellNotional)
        case Side.Sell =>
          (acc.quantity - trade.quantity, acc.buyNotional, acc.sellNotional + notional)
      }
      val commissions = acc.commissions + trade.commission
      if (quantity == 0L)
        Acc(0L, 0, 0, 0, (sellNotional - buyNotional - commissions) :: acc.completed)
      else
        Acc(quantity, buyNotional, sellNotional, commissions, acc.completed)
    }
    result.completed.reverse
  }
}
