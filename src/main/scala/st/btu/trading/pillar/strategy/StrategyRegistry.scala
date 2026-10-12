package st.btu.trading.pillar.strategy

/** Where registered strategies live for lookup by id, backing the backtest, sweep and alert
  * commands.
  */
trait StrategyRegistry[F[_]] {

  /** Adds `strategy` under its own id, replacing and logging a warning for a duplicate id. */
  def register(strategy: Strategy): F[Unit]

  /** The strategy registered under `id`, or `None` if there isn't one. */
  def get(id: StrategyId): F[Option[Strategy]]

  /** Every currently registered strategy, in no particular order. */
  def list: F[List[Strategy]]
}
