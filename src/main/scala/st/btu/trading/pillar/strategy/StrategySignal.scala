package st.btu.trading.pillar.strategy

import io.circe.{Decoder, Encoder}
import st.btu.trading.pillar.models.market.Ticker

/** An outcome of [[Strategy.onBar]]: buy, sell or do nothing. */
sealed trait StrategySignal

object StrategySignal {

  /** Opens or adds to a position in `ticker`, sized per `sizing`; `reason` is shown to the user. */
  final case class Buy(ticker: Ticker, sizing: Sizing, reason: String) extends StrategySignal

  /** Reduces or closes a position in `ticker`, sized per `sizing`; `reason` is shown to the user.
    */
  final case class Sell(ticker: Ticker, sizing: Sizing, reason: String) extends StrategySignal

  /** No action; `reason` documents why, for logging and display. */
  final case class Hold(reason: String) extends StrategySignal
}

/** How much to buy or sell, independent of which strategy decided to trade. */
sealed trait Sizing

object Sizing {

  /** Every share currently affordable (on a buy) or held (on a sell). */
  case object AllIn extends Sizing

  /** An exact share count. */
  final case class Quantity(n: Long) extends Sizing

  /** A fraction of total portfolio equity, converted to shares at the fill price. */
  final case class FractionOfEquity(fraction: BigDecimal) extends Sizing

  /** A cash amount, converted to shares at the fill price. */
  final case class Notional(amount: BigDecimal) extends Sizing

  /** Every share of the current position; sell only. */
  case object ClosePosition extends Sizing
}

/** Direction of a signal or a filled trade, kept separate from `StrategySignal.Buy`/`Sell` (which
  * also carry sizing and a reason) so a filled `Trade` can record just the executed direction.
  */
sealed trait Side {

  /** Lower-case wire and display form, e.g. `"buy"`. */
  def name: String
}

object Side {
  case object Buy extends Side { def name: String = "buy" }
  case object Sell extends Side { def name: String = "sell" }

  /** Parses [[Side.name]]'s own format; `None` for anything else. */
  def fromName(s: String): Option[Side] = s match {
    case "buy" => Some(Buy)
    case "sell" => Some(Sell)
    case _ => None
  }

  implicit val encoder: Encoder[Side] = Encoder.encodeString.contramap(_.name)

  implicit val decoder: Decoder[Side] =
    Decoder.decodeString.emap(s => fromName(s).toRight(s"Unknown side: $s"))
}

/** How close a prospective signal is to triggering.
  *
  * @param side
  *   the direction the signal would fire in
  * @param distanceToTrigger
  *   strategy-specific distance remaining; smaller means closer
  */
final case class PreSignal(side: Side, distanceToTrigger: BigDecimal, reason: String)
