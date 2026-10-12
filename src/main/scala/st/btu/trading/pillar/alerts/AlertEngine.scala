package st.btu.trading.pillar.alerts

import st.btu.trading.pillar.models.market.Quote

/** Pure evaluation of a [[PriceCrossed]] rule against a pair of consecutive quotes. Detecting a
  * cross (rather than just "current is past the threshold") needs the previous quote, so the caller
  * is responsible for remembering it between polls, per ticker.
  */
object AlertEngine {

  /** Evaluates `rule` against the previous and current quote.
    *
    * @return
    *   a `TargetHit` alert if the threshold was crossed since `previous`, else a `PreTarget` alert
    *   if `current` is within the rule's proximity band, else empty
    */
  def evaluatePriceRule(
    rule: PriceCrossed,
    previous: Option[Quote],
    current: Quote,
    atr: Option[BigDecimal]
  ): List[Alert] =
    if (previous.exists(p => hasCrossed(rule, p.price, current.price)))
      List(targetHitAlert(rule, current))
    else
      approaching(rule, current, atr).toList

  private def hasCrossed(
    rule: PriceCrossed,
    previousPrice: BigDecimal,
    currentPrice: BigDecimal
  ): Boolean =
    rule.direction match {
      case PriceCrossed.Direction.Above =>
        previousPrice < rule.threshold && currentPrice >= rule.threshold
      case PriceCrossed.Direction.Below =>
        previousPrice > rule.threshold && currentPrice <= rule.threshold
    }

  // Still on the "wrong" side of the threshold, but within the configured proximity band.
  private def approaching(
    rule: PriceCrossed,
    current: Quote,
    atr: Option[BigDecimal]
  ): Option[Alert] =
    for {
      proximity <- rule.proximity
      if onWrongSide(rule, current.price)
      band <- bandFor(proximity, rule.threshold, atr)
      distance = (current.price - rule.threshold).abs
      if distance <= band
    } yield preTargetAlert(rule, current, distance)

  private def onWrongSide(rule: PriceCrossed, price: BigDecimal): Boolean =
    rule.direction match {
      case PriceCrossed.Direction.Above => price < rule.threshold
      case PriceCrossed.Direction.Below => price > rule.threshold
    }

  private def bandFor(
    proximity: Proximity,
    threshold: BigDecimal,
    atr: Option[BigDecimal]
  ): Option[BigDecimal] =
    proximity match {
      case Proximity.Percent(p) => Some(threshold.abs * p / BigDecimal(100))
      case Proximity.Absolute(x) => Some(x)
      case Proximity.Atr(multiplier, _) => atr.map(_ * multiplier)
    }

  private def directionWord(direction: PriceCrossed.Direction): String = direction match {
    case PriceCrossed.Direction.Above => "rose above"
    case PriceCrossed.Direction.Below => "fell below"
  }

  private def targetHitAlert(rule: PriceCrossed, current: Quote): Alert =
    Alert(
      ruleKey = rule.ruleKey,
      symbol = Some(rule.ticker),
      kind = AlertKind.TargetHit,
      title = s"${rule.ticker.value} target hit",
      body =
        s"${rule.ticker.value} ${directionWord(rule.direction)} ${rule.threshold} (now ${current.price})",
      triggerValue = Some(current.price),
      at = current.asOf
    )

  private def preTargetAlert(rule: PriceCrossed, current: Quote, distance: BigDecimal): Alert =
    Alert(
      ruleKey = rule.ruleKey,
      symbol = Some(rule.ticker),
      kind = AlertKind.PreTarget,
      title = s"${rule.ticker.value} approaching target",
      body =
        s"${rule.ticker.value} at ${current.price}, ${distance.abs} away from ${rule.threshold}",
      triggerValue = Some(current.price),
      at = current.asOf
    )
}
