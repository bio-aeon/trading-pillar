package st.btu.trading.pillar.alerts

import st.btu.trading.pillar.strategy.Strategy

import java.time.ZonedDateTime

/** Live, per-binding evaluation of a registered strategy against current market data. */
trait LiveStrategyService[F[_]] {

  /** Evaluates `strategy` for `binding` as of `now` (`onBar` on a newly closed bar, plus
    * `proximity` on a provisional bar built from the latest quote), runs `act` on the evaluation
    * and returns what `act` returns.
    *
    * A bar and a warning count as handled only once `act` succeeds. If the evaluation or `act`
    * fails, the next call evaluates the same bar again, and its `act` sees the same signals and
    * alerts a second time. A handled bar is not evaluated again. A standing warning comes once per
    * session; within the session it comes again only of the other side, or closer to its signal and
    * at most half as far as the last one. A warning that a delivery guard holds back still counts
    * as its session's.
    *
    * Calls for one binding must run one after another: overlapping ones could both act on the same
    * bar.
    */
  def evaluate[A](binding: StrategyBinding, strategy: Strategy, now: ZonedDateTime)(
    act: LiveEvaluation => F[A]
  ): F[A]
}
