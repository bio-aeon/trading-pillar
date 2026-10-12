package st.btu.trading.pillar.alerts

import io.circe.{Decoder, Encoder}
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.strategy.StrategyId

import scala.concurrent.duration.FiniteDuration

/** A configured rule that can raise alerts through [[AlertDispatcher]]: either a price threshold
  * ([[PriceCrossed]]) or a live strategy binding ([[StrategyBinding]]).
  */
sealed trait AlertRule {
  def ruleKey: String
  def accountId: AccountId
  def ticker: Ticker
  def channels: List[String]
  def policy: DeliveryPolicy
}

/** A price-threshold rule: `proximity`, when set, drives `PreTarget` "approaching" alerts; the
  * threshold crossing itself drives a `TargetHit` alert. See `AlertEngine.evaluatePriceRule`.
  */
final case class PriceCrossed(
  id: AlertRuleId,
  accountId: AccountId,
  ticker: Ticker,
  threshold: BigDecimal,
  direction: PriceCrossed.Direction,
  proximity: Option[Proximity],
  channels: List[String],
  policy: DeliveryPolicy
) extends AlertRule {
  def ruleKey: String = PriceCrossed.ruleKeyFor(id)
}

object PriceCrossed {

  /** Which side of `threshold` triggers this rule. */
  sealed trait Direction {
    def name: String
  }

  object Direction {
    case object Above extends Direction { def name: String = "above" }
    case object Below extends Direction { def name: String = "below" }

    /** Parses [[Direction.name]]'s own format; `None` for anything else. */
    def fromName(s: String): Option[Direction] = s match {
      case "above" => Some(Above)
      case "below" => Some(Below)
      case _ => None
    }

    implicit val encoder: Encoder[Direction] = Encoder.encodeString.contramap(_.name)

    implicit val decoder: Decoder[Direction] =
      Decoder.decodeString.emap(s => fromName(s).toRight(s"Unknown direction: $s"))
  }

  def ruleKeyFor(id: AlertRuleId): String = s"price:${id.value}"
}

/** Input for `AlertRuleStorage.insertPriceRule`: `cooldown`/`maxPerDay` mirror the matching
  * [[DeliveryPolicy]] fields a caller can set explicitly; the storage applies `DeliveryPolicy`'s
  * own defaults when either is `None`. `hysteresisPct` and `quietHours` are not settable this way
  * yet, so an inserted rule always gets their defaults.
  */
final case class NewPriceRule(
  accountId: AccountId,
  ticker: Ticker,
  threshold: BigDecimal,
  direction: PriceCrossed.Direction,
  proximity: Option[Proximity],
  channels: List[String],
  cooldown: Option[FiniteDuration],
  maxPerDay: Option[Int]
)

/** Binds a registered strategy to a ticker for live evaluation; see [[LiveStrategyService]]. `mode
  * \== Paper` additionally routes every Buy/Sell signal through a paper broker, independent of (and
  * not subject to) the alert delivery guards.
  */
final case class StrategyBinding(
  id: StrategyBindingId,
  accountId: AccountId,
  strategyId: StrategyId,
  ticker: Ticker,
  mode: BindingMode,
  channels: List[String],
  policy: DeliveryPolicy
) extends AlertRule {
  def ruleKey: String = StrategyBinding.ruleKeyFor(id)
}

object StrategyBinding {
  def ruleKeyFor(id: StrategyBindingId): String = s"binding:${id.value}"
}
