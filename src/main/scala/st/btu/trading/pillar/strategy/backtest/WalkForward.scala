package st.btu.trading.pillar.strategy.backtest

import st.btu.trading.pillar.models.market.Bar
import st.btu.trading.pillar.strategy.Strategy
import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.LocalDate

/** One walk-forward step: `train` is warm-up history run immediately before `test` in the same
  * simulation, so the strategy's indicators and position are primed as they would be live; `test`
  * is the window performance is actually measured over. By construction (see
  * [[WalkForward.split]]), `train ++ test` is itself chronological with no gap or overlap.
  */
final case class Window(train: List[Bar], test: List[Bar])

/** Aggregate result of a walk-forward run over several windows.
  *
  * @param consistency
  *   fraction of windows with a successful, positive test return; a window that failed outright is
  *   excluded from both the numerator and the denominator
  */
final case class WalkForwardSummary(
  windows: Int,
  meanTestReturn: BigDecimal,
  worstTestReturn: BigDecimal,
  consistency: BigDecimal
)

/** Rolling-window out-of-sample evaluation: evaluates a strategy repeatedly on consecutive slices
  * of history instead of once over the whole series. trading-pillar's strategies have no fitting
  * step, so `train` here only supplies warm-up history; see [[evaluate]] for how that history is
  * kept from leaking into the test window's own P&L.
  */
object WalkForward {

  /** Splits `bars` into consecutive (train, test) windows measured in calendar days, not bar
    * counts, rolling forward by `testDays` each step.
    *
    * @return
    *   the windows found; empty if `bars` is empty or either day count isn't positive. The final
    *   window is dropped rather than truncated if `bars` doesn't fully cover its test span.
    */
  def split(bars: List[Bar], trainDays: Int, testDays: Int): List[Window] =
    if (bars.isEmpty || trainDays <= 0 || testDays <= 0) Nil
    else {
      val sorted = bars.sortBy(_.date.toEpochDay)
      val start = sorted.head.date
      val last = sorted.last.date

      def windowAt(stepIndex: Int): Option[Window] = {
        val trainStart = start.plusDays(stepIndex.toLong * testDays)
        val testStart = trainStart.plusDays(trainDays.toLong)
        val testEndExclusive = testStart.plusDays(testDays.toLong)
        // The test window is only complete if its last calendar day is actually covered by `bars`.
        if (testEndExclusive.minusDays(1).isAfter(last)) None
        else {
          val train = sorted.filter(b => !b.date.isBefore(trainStart) && b.date.isBefore(testStart))
          val test =
            sorted.filter(b => !b.date.isBefore(testStart) && b.date.isBefore(testEndExclusive))
          Some(Window(train, test))
        }
      }

      Iterator.iterate(0)(_ + 1).map(windowAt).takeWhile(_.isDefined).flatten.toList
    }

  /** Runs `strategy` once per window over `train ++ test`, then rebases the result to isolate the
    * test window's own performance.
    *
    * @return
    *   one entry per window, in order; a window whose `train ++ test` fails `BacktestEngine.run`'s
    *   own validation surfaces as a `Left` for that window only
    */
  def evaluate(
    strategy: Strategy,
    windows: List[Window],
    config: BacktestConfig
  ): List[Either[BacktestError, BacktestResult]] =
    windows.map(evaluateOne(strategy, _, config))

  /** Averages the total return and the positive-result share over the successful windows in
    * `results` only, and reports the worst.
    *
    * @return
    *   zero windows and zero figures when `results` is empty or every window failed
    */
  def summary(results: List[Either[BacktestError, BacktestResult]]): WalkForwardSummary = {
    val returns = results.collect { case Right(r) => r.metrics.totalReturn }
    if (returns.isEmpty) WalkForwardSummary(results.size, 0, 0, 0)
    else {
      val mean = divide(returns.sum, BigDecimal(returns.size))
      val consistency = divide(BigDecimal(returns.count(_ > 0)), BigDecimal(returns.size))
      WalkForwardSummary(results.size, mean, returns.min, consistency)
    }
  }

  private def evaluateOne(
    strategy: Strategy,
    window: Window,
    config: BacktestConfig
  ): Either[BacktestError, BacktestResult] =
    window.test.headOption match {
      case None => Left(BacktestError.NoBars)
      case Some(firstTestBar) =>
        BacktestEngine
          .run(strategy, window.train ++ window.test, config)
          .map(isolateTestWindow(strategy, config, window, firstTestBar.date, _))
    }

  private def isolateTestWindow(
    strategy: Strategy,
    config: BacktestConfig,
    window: Window,
    testStart: LocalDate,
    full: BacktestResult
  ): BacktestResult = {
    val testEquity = full.equityCurve.filter(!_.date.isBefore(testStart))
    val delta = testEquity.headOption.fold(BigDecimal(0))(config.initialCash - _.equity)
    val rebasedEquity =
      testEquity.map(p => p.copy(equity = p.equity + delta, cash = p.cash + delta))
    val testTrades = full.trades.filter(!_.date.isBefore(testStart))
    val testSkipped = full.skipped.filter(!_.date.isBefore(testStart))
    val lastTestBar = window.test.last
    val metrics = Metrics.compute(
      config.initialCash,
      rebasedEquity,
      testTrades,
      window.test.head.open,
      lastTestBar.close
    )
    BacktestResult(
      strategy.id,
      lastTestBar.ticker,
      strategy.parameters,
      testStart,
      lastTestBar.date,
      config,
      testTrades,
      rebasedEquity,
      metrics,
      testSkipped
    )
  }
}
