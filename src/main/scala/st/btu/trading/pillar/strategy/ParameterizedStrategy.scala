package st.btu.trading.pillar.strategy

import scala.util.Try

/** Opt-in capability for a [[Strategy]] that can rebuild itself from a string-keyed parameter map,
  * the same representation `Strategy.parameters` and the `/backtest`/`/sweep` commands use. Backs
  * ad hoc `/backtest` runs and `BacktestSweep`.
  */
trait ParameterizedStrategy extends Strategy {

  /** Rebuilds this strategy with `params` applied on top of its current values; a key absent from
    * `params` keeps this instance's current value for that parameter.
    *
    * @return
    *   a `Left` with a short, human-readable message - suitable to show directly in a Telegram
    *   reply - for an unknown key, an unparsable value, or a combination that violates the
    *   strategy's own invariants. Never throws.
    */
  def withParameters(params: Map[String, String]): Either[String, Strategy]
}

object ParameterizedStrategy {

  // Shared parsing helpers so every implementation reports unknown keys and unparsable values the
  // same way.

  private[pillar] def checkKnownKeys(
    params: Map[String, String],
    known: Set[String]
  ): Either[String, Unit] = {
    val unknown = params.keySet -- known
    Either.cond(
      unknown.isEmpty,
      (),
      s"Unknown parameter(s): ${unknown.toList.sorted.mkString(", ")}"
    )
  }

  private[pillar] def parseInt(key: String, value: String): Either[String, Int] =
    Try(value.toInt).toOption.toRight(s"Invalid value for $key: $value")

  private[pillar] def parseBigDecimal(key: String, value: String): Either[String, BigDecimal] =
    Try(BigDecimal(value)).toOption.toRight(s"Invalid value for $key: $value")

  // Builds the new strategy via `build`, turning a constructor `require` failure (e.g. an
  // invariant like fast < slow) into a Left instead of letting it throw.
  private[pillar] def buildChecked[A <: Strategy](build: => A): Either[String, Strategy] =
    Try(build).toEither.left.map(e => Option(e.getMessage).getOrElse(e.toString))
}
