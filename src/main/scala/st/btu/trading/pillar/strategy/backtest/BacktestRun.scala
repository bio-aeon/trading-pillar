package st.btu.trading.pillar.strategy.backtest

import st.btu.trading.pillar.strategy.BacktestRunId

import java.time.ZonedDateTime

/** A persisted backtest run: `result` as it was computed, with its storage id and timestamp. */
final case class BacktestRun(id: BacktestRunId, createdAt: ZonedDateTime, result: BacktestResult)
