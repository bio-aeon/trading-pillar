package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.alerts.{PriceCrossed, Proximity}

import scala.concurrent.duration._
import scala.util.Try

final case class ParsedPriceRule(
  direction: PriceCrossed.Direction,
  threshold: BigDecimal,
  proximity: Option[Proximity],
  cooldown: Option[FiniteDuration],
  maxPerDay: Option[Int]
)

// Pure parser for the tokens following "/alert price SYM": a comparison token, a threshold, then
// any of proximity=/cooldown=/max= in any order. AlertRuleHandler only distinguishes success from
// failure (replying with the one-line usage string on any Left), so the Left reason here is
// informational -- asserted by this object's own spec -- rather than user-facing.
object PriceRuleParser {

  private val PercentPattern = """^(-?\d+(?:\.\d+)?)%$""".r
  private val AtrPattern = """^(-?\d+(?:\.\d+)?)atr(\d+)$""".r
  private val AbsolutePattern = """^(-?\d+(?:\.\d+)?)$""".r
  private val CooldownPattern = """^(\d+)(m|h|d)$""".r

  def parse(tokens: List[String]): Either[String, ParsedPriceRule] = tokens match {
    case cmpRaw :: thresholdRaw :: optionTokens =>
      for {
        direction <- parseDirection(cmpRaw)
        threshold <- parseThreshold(thresholdRaw)
        options <- parseOptions(optionTokens)
      } yield ParsedPriceRule(
        direction,
        threshold,
        options.proximity,
        options.cooldown,
        options.maxPerDay
      )
    case _ => Left("missing comparison and threshold")
  }

  private final case class Options(
    proximity: Option[Proximity] = None,
    cooldown: Option[FiniteDuration] = None,
    maxPerDay: Option[Int] = None
  )

  private def parseOptions(tokens: List[String]): Either[String, Options] =
    tokens.foldLeft[Either[String, Options]](Right(Options())) { (acc, token) =>
      acc.flatMap(applyOption(token, _))
    }

  private def applyOption(token: String, opts: Options): Either[String, Options] =
    token.split("=", 2).toList match {
      case key :: value :: Nil =>
        key.toLowerCase match {
          case "proximity" => parseProximity(value).map(p => opts.copy(proximity = Some(p)))
          case "cooldown" => parseCooldown(value).map(c => opts.copy(cooldown = Some(c)))
          case "max" => parseMax(value).map(m => opts.copy(maxPerDay = Some(m)))
          case _ => Left(s"unknown option: $token")
        }
      case _ => Left(s"unknown option: $token")
    }

  private def parseDirection(raw: String): Either[String, PriceCrossed.Direction] =
    raw.toLowerCase match {
      case ">" => Right(PriceCrossed.Direction.Above)
      case "<" => Right(PriceCrossed.Direction.Below)
      case other => PriceCrossed.Direction.fromName(other).toRight(s"invalid comparison: $other")
    }

  private def parseThreshold(raw: String): Either[String, BigDecimal] =
    Try(BigDecimal(raw)).toOption.toRight(s"invalid threshold: $raw")

  private def parseProximity(raw: String): Either[String, Proximity] =
    raw.toLowerCase match {
      case PercentPattern(p) => Right(Proximity.Percent(BigDecimal(p)))
      case AtrPattern(m, period) => Right(Proximity.Atr(BigDecimal(m), period.toInt))
      case AbsolutePattern(x) => Right(Proximity.Absolute(BigDecimal(x)))
      case other => Left(s"invalid proximity: $other")
    }

  private def parseCooldown(raw: String): Either[String, FiniteDuration] =
    raw.toLowerCase match {
      case CooldownPattern(amount, "m") => Right(amount.toLong.minutes)
      case CooldownPattern(amount, "h") => Right(amount.toLong.hours)
      case CooldownPattern(amount, "d") => Right(amount.toLong.days)
      case other => Left(s"invalid cooldown: $other")
    }

  private def parseMax(raw: String): Either[String, Int] =
    Try(raw.toInt).toOption.filter(_ > 0).toRight(s"invalid max: $raw")
}
