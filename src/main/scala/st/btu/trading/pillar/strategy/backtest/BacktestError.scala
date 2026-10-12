package st.btu.trading.pillar.strategy.backtest

/** Why a backtest could not run or complete. */
sealed trait BacktestError {
  def message: String
}

object BacktestError {

  /** The bars given were not chronological with unique dates. */
  final case class InvalidBars(message: String) extends BacktestError

  /** No bars were given at all. */
  case object NoBars extends BacktestError {
    val message: String = "No bars provided"
  }

  /** Fewer bars were given than the strategy's own `warmupBars`. */
  final case class NotEnoughBars(required: Int, actual: Int) extends BacktestError {
    def message: String = s"Not enough bars: required $required, got $actual"
  }

  /** A `ParameterizedStrategy.withParameters` call failed for one point of a sweep grid; `message`
    * is that failure's own message, unchanged.
    */
  final case class InvalidStrategy(message: String) extends BacktestError
}
