package st.btu.trading.pillar.analytics.portfolio

import st.btu.trading.pillar.strategy.Side

/** One target's actual weight against its configured target, both in percent (not a 0-1 fraction,
  * unlike `Allocation`'s own weight maps).
  */
final case class Drift(
  dimension: Dimension,
  key: String,
  targetPct: BigDecimal,
  actualPct: BigDecimal,
  driftPct: BigDecimal,
  breached: Boolean
)

/** A rebalancing suggestion for one target, in the account's base currency; always positive, with
  * `side` carrying the direction.
  */
final case class Suggestion(dimension: Dimension, key: String, side: Side, amount: BigDecimal)

/** Turns a position snapshot and its allocation into drift readings and rebalancing suggestions
  * against this account's configured targets. Both entry points silently drop disabled targets and
  * preserve `targets`' own order in their result list.
  */
object Rebalancer {

  private val Zero = BigDecimal(0)
  private val Hundred = BigDecimal(100)

  /** Compares `snapshot`'s actual weights against `targets`, one [[Drift]] per enabled target.
    *
    * @return
    *   `driftPct` is `actualPct - targetPct` in percentage points; `breached` when its absolute
    *   value exceeds the target's own `driftThresholdPct`. A target matching no weight at all gets
    *   `actualPct = 0`.
    */
  def drift(
    allocation: Allocation,
    snapshot: PositionSnapshot,
    targets: List[AllocationTarget]
  ): List[Drift] = {
    val tickerWeights = actualByTicker(snapshot)
    targets.filter(_.enabled).map { t =>
      val actualPct = actualWeight(allocation, tickerWeights, t.dimension, t.key) * Hundred
      val driftPct = actualPct - t.targetPct
      Drift(
        t.dimension,
        t.key,
        t.targetPct,
        actualPct,
        driftPct,
        breached = driftPct.abs > t.driftThresholdPct
      )
    }
  }

  /** Suggests trades to bring `snapshot` toward `targets`.
    *
    * @param newCash
    *   when given, only buys toward each underweight target's post-deposit amount, spreading an
    *   insufficient amount proportionally by shortfall, and never sells. When absent, rebalances
    *   both ways against the current total. Either way, a target already at its target amount gets
    *   no suggestion.
    */
  def suggest(
    snapshot: PositionSnapshot,
    targets: List[AllocationTarget],
    newCash: Option[BigDecimal]
  ): List[Suggestion] = {
    val enabled = targets.filter(_.enabled)
    newCash match {
      case Some(cash) => suggestWithCash(snapshot, enabled, cash)
      case None => suggestWithoutCash(snapshot, enabled)
    }
  }

  private def suggestWithoutCash(
    snapshot: PositionSnapshot,
    targets: List[AllocationTarget]
  ): List[Suggestion] = {
    val allocation = Allocation.of(snapshot)
    val tickerWeights = actualByTicker(snapshot)
    targets.flatMap { t =>
      val actualAmount =
        actualWeight(allocation, tickerWeights, t.dimension, t.key) * snapshot.total
      val targetAmount = t.targetPct / Hundred * snapshot.total
      toSuggestion(t, targetAmount - actualAmount)
    }
  }

  private def suggestWithCash(
    snapshot: PositionSnapshot,
    targets: List[AllocationTarget],
    newCash: BigDecimal
  ): List[Suggestion] = {
    val allocation = Allocation.of(snapshot)
    val tickerWeights = actualByTicker(snapshot)
    val postTotal = snapshot.total + newCash
    val shortfalls = targets.map { t =>
      val actualAmount =
        actualWeight(allocation, tickerWeights, t.dimension, t.key) * snapshot.total
      val targetAmount = t.targetPct / Hundred * postTotal
      t -> (targetAmount - actualAmount)
    }
    val underweight = shortfalls.filter { case (_, shortfall) => shortfall > Zero }
    val totalShortfall = underweight.iterator.map(_._2).sum
    if (totalShortfall <= Zero) Nil
    else if (newCash >= totalShortfall)
      underweight.map { case (t, shortfall) => Suggestion(t.dimension, t.key, Side.Buy, shortfall) }
    else
      underweight.map { case (t, shortfall) =>
        Suggestion(t.dimension, t.key, Side.Buy, newCash * shortfall / totalShortfall)
      }
  }

  private def toSuggestion(t: AllocationTarget, diff: BigDecimal): Option[Suggestion] =
    if (diff > Zero) Some(Suggestion(t.dimension, t.key, Side.Buy, diff))
    else if (diff < Zero) Some(Suggestion(t.dimension, t.key, Side.Sell, -diff))
    else None

  private def actualWeight(
    allocation: Allocation,
    tickerWeights: Map[String, BigDecimal],
    dimension: Dimension,
    key: String
  ): BigDecimal = {
    val breakdown = dimension match {
      case Dimension.InstrumentType => allocation.byInstrumentType
      case Dimension.Currency => allocation.byCurrency
      case Dimension.Exchange => allocation.byExchange
      case Dimension.Ticker => tickerWeights
    }
    breakdown.getOrElse(key, Zero)
  }

  private def actualByTicker(snapshot: PositionSnapshot): Map[String, BigDecimal] =
    snapshot.positions
      .flatMap(p => p.ticker.flatMap(t => p.weight.map(w => t.value -> w)))
      .groupMapReduce(_._1)(_._2)(_ + _)
}
