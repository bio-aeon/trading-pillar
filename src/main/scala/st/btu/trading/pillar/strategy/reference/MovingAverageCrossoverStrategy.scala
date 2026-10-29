package st.btu.trading.pillar.strategy.reference

import st.btu.trading.pillar.models.market.Bar
import st.btu.trading.pillar.strategy.indicators.Indicators
import st.btu.trading.pillar.strategy.*

// Example/test-only reference strategy (trading-pillar stays consumer-strategy-free otherwise).
// Buys AllIn when the fast SMA crosses above the slow SMA and there is no position; sells
// ClosePosition on the opposite cross while holding a position.
final class MovingAverageCrossoverStrategy(
  fast: Int,
  slow: Int,
  proximityThreshold: BigDecimal = MovingAverageCrossoverStrategy.DefaultProximityThreshold
) extends ParameterizedStrategy {
  require(fast > 0, "fast must be positive")
  require(slow > 0, "slow must be positive")
  require(fast < slow, "fast must be less than slow")

  val id: StrategyId = StrategyId(s"ma-cross-$fast-$slow")

  val name: String = s"Moving Average Crossover ($fast/$slow)"

  val parameters: Map[String, String] = Map("fast" -> fast.toString, "slow" -> slow.toString)

  val warmupBars: Int = slow + 1

  def onBar(ctx: StrategyContext, bar: Bar): List[StrategySignal] = {
    val closes = ctx.history.closes
    (for {
      currFast <- Indicators.sma(closes, fast)
      currSlow <- Indicators.sma(closes, slow)
      prevFast <- Indicators.sma(closes.dropRight(1), fast)
      prevSlow <- Indicators.sma(closes.dropRight(1), slow)
    } yield {
      val crossedUp = prevFast <= prevSlow && currFast > currSlow
      val crossedDown = prevFast >= prevSlow && currFast < currSlow
      if (crossedUp && ctx.position.isEmpty)
        List(
          StrategySignal.Buy(ctx.ticker, Sizing.AllIn, s"$name: fast SMA crossed above slow SMA")
        )
      else if (crossedDown && ctx.position.isDefined)
        List(
          StrategySignal
            .Sell(ctx.ticker, Sizing.ClosePosition, s"$name: fast SMA crossed below slow SMA")
        )
      else
        List(StrategySignal.Hold(s"$name: no crossover"))
    }).getOrElse(List(StrategySignal.Hold(s"$name: insufficient history")))
  }

  override def proximity(ctx: StrategyContext, bar: Bar): Option[PreSignal] =
    for {
      storedLead <- fastLead(ctx.history.closes)
      liveLead <- fastLead(ctx.history.closes :+ bar.close)
      side <- convergingSide(ctx.position, storedLead, liveLead)
      distance = liveLead.abs
      if distance < proximityThreshold
    } yield PreSignal(side, distance, s"$name: fast/slow SMA distance $distance")

  private def fastLead(closes: Vector[BigDecimal]): Option[BigDecimal] =
    for {
      fastSma <- Indicators.sma(closes, fast)
      slowSma <- Indicators.sma(closes, slow)
      if slowSma != BigDecimal(0)
    } yield Indicators.divide(fastSma - slowSma, slowSma)

  // A crossover approaches only while the live bar brings the fast SMA nearer the slow one: from
  // below while flat, toward a buy, and from above while holding, toward a sell. Right after a
  // crossover the averages move apart, however close they still are.
  private def convergingSide(
    position: Option[OpenPosition],
    storedLead: BigDecimal,
    liveLead: BigDecimal
  ): Option[Side] =
    position match {
      case None => Option.when(liveLead < 0 && liveLead > storedLead)(Side.Buy)
      case Some(_) => Option.when(liveLead > 0 && liveLead < storedLead)(Side.Sell)
    }

  // Keys: "fast", "slow", "proximityThreshold". Unset keys keep this instance's current value;
  // fast < slow (like every other constructor invariant) is checked by building the candidate
  // instance and turning a failed `require` into a Left.
  def withParameters(params: Map[String, String]): Either[String, Strategy] =
    for {
      _ <- ParameterizedStrategy.checkKnownKeys(
        params,
        MovingAverageCrossoverStrategy.ParameterKeys
      )
      newFast <- params
        .get("fast")
        .fold[Either[String, Int]](Right(fast))(ParameterizedStrategy.parseInt("fast", _))
      newSlow <- params
        .get("slow")
        .fold[Either[String, Int]](Right(slow))(ParameterizedStrategy.parseInt("slow", _))
      newProximityThreshold <- params
        .get("proximityThreshold")
        .fold[Either[String, BigDecimal]](Right(proximityThreshold))(
          ParameterizedStrategy.parseBigDecimal("proximityThreshold", _)
        )
      strategy <- ParameterizedStrategy.buildChecked(
        new MovingAverageCrossoverStrategy(newFast, newSlow, newProximityThreshold)
      )
    } yield strategy
}

object MovingAverageCrossoverStrategy {
  val DefaultProximityThreshold: BigDecimal = BigDecimal("0.01")

  private val ParameterKeys = Set("fast", "slow", "proximityThreshold")
}
