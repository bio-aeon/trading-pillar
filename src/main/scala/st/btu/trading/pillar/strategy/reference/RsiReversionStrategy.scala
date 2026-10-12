package st.btu.trading.pillar.strategy.reference

import st.btu.trading.pillar.models.market.Bar
import st.btu.trading.pillar.strategy.indicators.Indicators
import st.btu.trading.pillar.strategy.*

// Example/test-only reference strategy. Buys AllIn when RSI crosses up through `lower` with no
// position; sells ClosePosition when RSI crosses down through `upper` while holding a position.
final class RsiReversionStrategy(period: Int, lower: BigDecimal, upper: BigDecimal)
    extends ParameterizedStrategy {
  require(period > 0, "period must be positive")
  require(lower < upper, "lower must be less than upper")

  val id: StrategyId = StrategyId(s"rsi-reversion-$period-$lower-$upper")

  val name: String = s"RSI Reversion ($period, $lower/$upper)"

  val parameters: Map[String, String] =
    Map("period" -> period.toString, "lower" -> lower.toString, "upper" -> upper.toString)

  // Needs both the current and the previous RSI(period) value, each requiring period+1 closes.
  val warmupBars: Int = period + 2

  def onBar(ctx: StrategyContext, bar: Bar): List[StrategySignal] = {
    val closes = ctx.history.closes
    (for {
      currRsi <- Indicators.rsi(closes, period)
      prevRsi <- Indicators.rsi(closes.dropRight(1), period)
    } yield {
      val crossedUpThroughLower = prevRsi <= lower && currRsi > lower
      val crossedDownThroughUpper = prevRsi >= upper && currRsi < upper
      if (crossedUpThroughLower && ctx.position.isEmpty)
        List(StrategySignal.Buy(ctx.ticker, Sizing.AllIn, s"$name: RSI crossed up through $lower"))
      else if (crossedDownThroughUpper && ctx.position.isDefined)
        List(
          StrategySignal
            .Sell(ctx.ticker, Sizing.ClosePosition, s"$name: RSI crossed down through $upper")
        )
      else
        List(StrategySignal.Hold(s"$name: no reversion signal"))
    }).getOrElse(List(StrategySignal.Hold(s"$name: insufficient history")))
  }

  // Keys: "period", "lower", "upper". Unset keys keep this instance's current value; lower < upper
  // (like every other constructor invariant) is checked by building the candidate instance and
  // turning a failed `require` into a Left.
  def withParameters(params: Map[String, String]): Either[String, Strategy] =
    for {
      _ <- ParameterizedStrategy.checkKnownKeys(params, RsiReversionStrategy.ParameterKeys)
      newPeriod <- params
        .get("period")
        .fold[Either[String, Int]](Right(period))(ParameterizedStrategy.parseInt("period", _))
      newLower <- params
        .get("lower")
        .fold[Either[String, BigDecimal]](Right(lower))(
          ParameterizedStrategy.parseBigDecimal("lower", _)
        )
      newUpper <- params
        .get("upper")
        .fold[Either[String, BigDecimal]](Right(upper))(
          ParameterizedStrategy.parseBigDecimal("upper", _)
        )
      strategy <- ParameterizedStrategy.buildChecked(
        new RsiReversionStrategy(newPeriod, newLower, newUpper)
      )
    } yield strategy
}

object RsiReversionStrategy {
  private val ParameterKeys = Set("period", "lower", "upper")
}
