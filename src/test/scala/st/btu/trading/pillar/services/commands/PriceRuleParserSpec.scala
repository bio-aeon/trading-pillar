package st.btu.trading.pillar.services.commands

import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.{PriceCrossed, Proximity}

import scala.concurrent.duration._

class PriceRuleParserSpec extends Specification {

  "PriceRuleParser" >> {
    "parse" >> {

      "parses a bare '>' comparison and threshold with no options" >> {
        PriceRuleParser.parse(List(">", "300")) must beRight(
          ParsedPriceRule(PriceCrossed.Direction.Above, BigDecimal(300), None, None, None)
        )
      }

      "parses a bare '<' comparison and threshold" >> {
        PriceRuleParser.parse(List("<", "150")) must beRight(
          ParsedPriceRule(PriceCrossed.Direction.Below, BigDecimal(150), None, None, None)
        )
      }

      "accepts 'above' and 'below' as comparison words, case-insensitively" >> {
        PriceRuleParser.parse(List("Above", "300")).map(_.direction) must
          beRight(PriceCrossed.Direction.Above: PriceCrossed.Direction)
        PriceRuleParser.parse(List("BELOW", "150")).map(_.direction) must
          beRight(PriceCrossed.Direction.Below: PriceCrossed.Direction)
      }

      "parses a decimal threshold" >> {
        PriceRuleParser.parse(List(">", "150.5")).map(_.threshold) must beRight(BigDecimal("150.5"))
      }

      "parses proximity=1% as a 1 percent proximity" >> {
        PriceRuleParser.parse(List(">", "300", "proximity=1%")).map(_.proximity) must
          beRight(Some(Proximity.Percent(BigDecimal(1))): Option[Proximity])
      }

      "parses proximity=2.5 as an absolute proximity of 2.5" >> {
        PriceRuleParser.parse(List(">", "300", "proximity=2.5")).map(_.proximity) must
          beRight(Some(Proximity.Absolute(BigDecimal("2.5"))): Option[Proximity])
      }

      "parses proximity=0.5atr14 as 0.5 ATR over a 14-period window" >> {
        PriceRuleParser.parse(List(">", "300", "proximity=0.5atr14")).map(_.proximity) must
          beRight(Some(Proximity.Atr(BigDecimal("0.5"), 14)): Option[Proximity])
      }

      "parses the proximity option case-insensitively" >> {
        PriceRuleParser.parse(List(">", "300", "PROXIMITY=0.5ATR14")).map(_.proximity) must
          beRight(Some(Proximity.Atr(BigDecimal("0.5"), 14)): Option[Proximity])
      }

      "parses cooldown=15m, 2h and 1d" >> {
        PriceRuleParser.parse(List(">", "300", "cooldown=15m")).map(_.cooldown) must
          beRight(Some(15.minutes): Option[FiniteDuration])
        PriceRuleParser.parse(List(">", "300", "cooldown=2h")).map(_.cooldown) must
          beRight(Some(2.hours): Option[FiniteDuration])
        PriceRuleParser.parse(List(">", "300", "cooldown=1d")).map(_.cooldown) must
          beRight(Some(1.day): Option[FiniteDuration])
      }

      "parses max=5" >> {
        PriceRuleParser.parse(List(">", "300", "max=5")).map(_.maxPerDay) must beRight(Some(5))
      }

      "parses proximity, cooldown and max together, in any order" >> {
        PriceRuleParser.parse(
          List(">", "300", "max=5", "proximity=1%", "cooldown=15m")
        ) must beRight(
          ParsedPriceRule(
            PriceCrossed.Direction.Above,
            BigDecimal(300),
            Some(Proximity.Percent(BigDecimal(1))),
            Some(15.minutes),
            Some(5)
          )
        )
      }

      "fails on an invalid comparison token" >> {
        PriceRuleParser.parse(List("=", "300")) must beLeft
      }

      "fails on a non-numeric threshold" >> {
        PriceRuleParser.parse(List(">", "abc")) must beLeft
      }

      "fails on an invalid proximity value" >> {
        PriceRuleParser.parse(List(">", "300", "proximity=abc")) must beLeft
      }

      "fails on an invalid cooldown value (missing unit)" >> {
        PriceRuleParser.parse(List(">", "300", "cooldown=15")) must beLeft
      }

      "fails on an invalid cooldown unit" >> {
        PriceRuleParser.parse(List(">", "300", "cooldown=15s")) must beLeft
      }

      "fails on a non-positive max" >> {
        PriceRuleParser.parse(List(">", "300", "max=0")) must beLeft
        PriceRuleParser.parse(List(">", "300", "max=-1")) must beLeft
      }

      "fails on a non-numeric max" >> {
        PriceRuleParser.parse(List(">", "300", "max=abc")) must beLeft
      }

      "fails on an unknown option key" >> {
        PriceRuleParser.parse(List(">", "300", "bogus=1")) must beLeft
      }

      "fails on a malformed option token with no '='" >> {
        PriceRuleParser.parse(List(">", "300", "proximity")) must beLeft
      }

      "fails when the comparison or threshold is missing" >> {
        PriceRuleParser.parse(List(">")) must beLeft
        PriceRuleParser.parse(Nil) must beLeft
      }
    }
  }
}
