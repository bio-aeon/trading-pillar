package st.btu.trading.pillar.storages

import st.btu.trading.pillar.strategy.BacktestRunId
import st.btu.trading.pillar.strategy.backtest.{BacktestResult, BacktestRun}

import java.time.ZonedDateTime

/** Persisted backtest runs. */
trait BacktestRunStorage[F[_]] {

  /** Persists `result`, run at `createdAt`. */
  def save(result: BacktestResult, createdAt: ZonedDateTime): F[BacktestRunId]

  /** The run with this id, or `None` if there isn't one. */
  def get(id: BacktestRunId): F[Option[BacktestRun]]

  /** The most recent runs, newest first. */
  def recent(limit: Int): F[List[BacktestRun]]
}
