package st.btu.trading.pillar.paper

import st.btu.trading.pillar.alerts.StrategyBinding
import st.btu.trading.pillar.models.market.Quote
import st.btu.trading.pillar.strategy.StrategySignal

import java.time.ZonedDateTime

/** Executes a live Buy/Sell signal against a binding's virtual paper account and position. Unlike
  * the backtest engine's next-open fill rule, a live paper fill uses the latest available quote
  * (there is no next bar to wait for outside a backtest), adjusted by slippage exactly like the
  * backtest engine. `Sizing.FractionOfEquity` is resolved against cash plus this signal's own
  * ticker's position only, not the account's other positions.
  */
trait PaperBroker[F[_]] {

  /** Fills, or rejects, `signal` for `binding` at `quote`'s price. Always returns a `PaperOrder`
    * rather than raising - a rejection is an expected outcome, not a failure.
    */
  def execute(
    binding: StrategyBinding,
    signal: StrategySignal,
    quote: Quote,
    now: ZonedDateTime
  ): F[PaperOrder]
}
