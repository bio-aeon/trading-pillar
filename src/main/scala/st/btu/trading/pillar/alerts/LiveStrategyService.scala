package st.btu.trading.pillar.alerts

import st.btu.trading.pillar.strategy.Strategy

import java.time.ZonedDateTime

/** Live, per-binding evaluation of a registered strategy against current market data. */
trait LiveStrategyService[F[_]] {

  /** Evaluates `strategy` for `binding` as of `now`: `onBar` on a newly closed bar (once per bar
    * date, tracked internally per binding), plus `proximity` on a provisional bar built from the
    * latest quote.
    */
  def evaluate(binding: StrategyBinding, strategy: Strategy, now: ZonedDateTime): F[LiveEvaluation]
}
