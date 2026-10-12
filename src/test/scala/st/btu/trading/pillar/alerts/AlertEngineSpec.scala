package st.btu.trading.pillar.alerts

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Quote, Ticker}
import st.btu.trading.pillar.testkit.AlertFakes

import java.time.{ZoneOffset, ZonedDateTime}

class AlertEngineSpec extends Specification {

  private val ticker = Ticker("AAPL")
  private val t0 = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)

  private def q(price: BigDecimal, at: ZonedDateTime = t0): Quote =
    AlertFakes.quote(ticker, price, at)

  private val rule = AlertFakes.priceCrossed(
    ticker = ticker,
    threshold = 100,
    direction = PriceCrossed.Direction.Above,
    proximity = Some(Proximity.Percent(1))
  )

  "AlertEngine" >> {
    "evaluatePriceRule" >> {
      "emits nothing on the first observation, even if already past the threshold" >> {
        AlertEngine.evaluatePriceRule(rule, None, q(105), None) must beEmpty
      }

      "emits a PreTarget alert when approaching the threshold from below, within the proximity band" >> {
        val alerts = AlertEngine.evaluatePriceRule(rule, Some(q(98)), q(99.5), None)
        alerts must haveSize(1)
        alerts.head.kind must_== AlertKind.PreTarget
        alerts.head.triggerValue must beSome(BigDecimal(99.5))
      }

      "emits nothing when still far from the threshold" >> {
        AlertEngine.evaluatePriceRule(rule, Some(q(80)), q(81), None) must beEmpty
      }

      "emits a TargetHit alert on an upward cross" >> {
        val alerts = AlertEngine.evaluatePriceRule(rule, Some(q(99)), q(101), None)
        alerts must haveSize(1)
        alerts.head.kind must_== AlertKind.TargetHit
        alerts.head.triggerValue must beSome(BigDecimal(101))
      }

      "emits nothing once already past the threshold and staying there (no repeat cross)" >> {
        AlertEngine.evaluatePriceRule(rule, Some(q(101)), q(102), None) must beEmpty
      }

      "produces pre_target then target_hit as price approaches and then crosses" >> {
        val approaching = AlertEngine.evaluatePriceRule(rule, Some(q(98)), q(99.5), None)
        val crossing = AlertEngine.evaluatePriceRule(rule, Some(q(99.5)), q(100.2), None)
        approaching.map(_.kind) must_== List(AlertKind.PreTarget)
        crossing.map(_.kind) must_== List(AlertKind.TargetHit)
      }

      "supports the Below direction symmetrically" >> {
        val belowRule = rule.copy(direction = PriceCrossed.Direction.Below, threshold = 50)
        val alerts = AlertEngine.evaluatePriceRule(belowRule, Some(q(51)), q(49), None)
        alerts.map(_.kind) must_== List(AlertKind.TargetHit)
      }

      "uses Absolute proximity as a fixed price band" >> {
        val absRule = rule.copy(proximity = Some(Proximity.Absolute(2)))
        AlertEngine.evaluatePriceRule(absRule, Some(q(97)), q(98.5), None).map(_.kind) must_== List(
          AlertKind.PreTarget
        )
        AlertEngine.evaluatePriceRule(absRule, Some(q(90)), q(90), None) must beEmpty
      }

      "uses ATR proximity scaled by the caller-supplied ATR value" >> {
        val atrRule = rule.copy(proximity = Some(Proximity.Atr(BigDecimal(2), 14)))
        AlertEngine.evaluatePriceRule(
          atrRule,
          Some(q(97)),
          q(97),
          atr = Some(BigDecimal(1))
        ) must beEmpty
        AlertEngine
          .evaluatePriceRule(atrRule, Some(q(97)), q(97), atr = Some(BigDecimal(2)))
          .map(_.kind) must_== List(AlertKind.PreTarget)
      }

      "does not use proximity when the rule has none configured" >> {
        val noProximityRule = rule.copy(proximity = None)
        AlertEngine.evaluatePriceRule(noProximityRule, Some(q(99.9)), q(99.95), None) must beEmpty
      }
    }
  }
}
