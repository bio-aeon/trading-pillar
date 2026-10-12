package st.btu.trading.pillar.strategy.backtest

import st.btu.trading.pillar.models.market.{Source, Ticker}
import st.btu.trading.pillar.strategy.{Strategy, StrategyId}

import java.time.LocalDate

/** Runs a strategy over stored bars and persists the result. */
trait BacktestService[F[_]] {

  /** Looks up `strategyId` in the registry and backtests it on `ticker`'s bars from `source`
    * between `from` and `to`, then persists the result.
    *
    * @return
    *   a failed `F` with `BacktestServiceError.StrategyNotFound` if the id isn't registered, or
    *   `BacktestServiceError.Failed` if the backtest itself fails
    */
  def run(
    strategyId: StrategyId,
    ticker: Ticker,
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun]

  /** Same as [[run]], but for an ad hoc strategy instance the caller already has (e.g. a registered
    * `ParameterizedStrategy` rebuilt with overridden parameters), so this never fails with
    * `StrategyNotFound`. The result is persisted exactly like `run`'s.
    */
  def runStrategy(
    strategy: Strategy,
    ticker: Ticker,
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun]

  /** Multi-ticker backtest: every ticker's bars are loaded from the same `source` and run as one
    * portfolio sharing a single cash balance, then persisted as one `BacktestRun` whose
    * `BacktestResult.ticker` is `tickers` joined by `","`.
    */
  def runPortfolio(
    strategyId: StrategyId,
    tickers: List[Ticker],
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun]
}

/** Failure reported by [[BacktestService]]. */
sealed abstract class BacktestServiceError(msg: String) extends Exception(msg)

object BacktestServiceError {

  /** No strategy is registered under `id`. */
  final case class StrategyNotFound(id: StrategyId)
      extends BacktestServiceError(s"Strategy not found: ${id.value}")

  /** The backtest itself failed; see `error` for why. */
  final case class Failed(error: BacktestError) extends BacktestServiceError(error.message)
}
