# Strategies

Package `st.btu.trading.pillar.strategy` (contract, indicators, registry) and
`strategy.reference` (examples/tests only). See [`backtesting.md`](backtesting.md) for how signals
turn into trades and [`alerts.md`](alerts.md) for how they turn into live notifications.

## The `Strategy` contract

```scala
trait Strategy {
  def id: StrategyId
  def name: String
  def parameters: Map[String, String]        // recorded with every backtest run
  def warmupBars: Int                         // minimum history before onBar is called
  def onBar(ctx: StrategyContext, bar: Bar): List[StrategySignal]
  def proximity(ctx: StrategyContext, bar: Bar): Option[PreSignal] = None  // bar: the live bar to include
}
```

`StrategyId` is an opaque type over `String` (`StrategyId(value)`, `id.value`) - a stable
identifier used in Telegram commands (`/backtest <strategyId> ...`), in `StrategyBinding`s and in
persisted backtest runs.

**User strategies never live in trading-pillar.** The library ships at most a handful of reference
strategies (`strategy.reference`) as examples and test fixtures; a consumer application implements
`Strategy` in its own codebase and calls `StrategyRegistry.register` at startup (see the README's
Quick Start, whose `MaCross50x200` example shows the composition pattern required when the strategy
you want to reuse, like `MovingAverageCrossoverStrategy`, is `final`).

### `warmupBars`

`warmupBars` is the minimum number of bars `StrategyContext.history` must contain before `onBar` is
invoked at all:

- **Backtest** (`BacktestEngine.run`): rejects the whole run up front with
  `BacktestError.NotEnoughBars(required, actual)` if `bars.size < strategy.warmupBars`; during the
  simulation, `onBar` is only called once `history.size >= warmupBars` for the bar being processed
  (earlier bars still advance `history` and pending-order fills, just without calling `onBar`).
- **Live** (`LiveStrategyServiceImpl.evaluate`): fetches exactly `strategy.warmupBars` bars via
  `PriceHistoryStorage.latestBars(ticker, source, strategy.warmupBars)`; if fewer are available,
  `onBar` is skipped for that poll (no error - it just waits for more history to accumulate).

`proximity` has no warmup gate - it is evaluated whenever a live quote is available, even before
`warmupBars` bars exist (the reference `MovingAverageCrossoverStrategy.proximity` simply returns
`None` until its own `Indicators.sma` calls have enough data).

### History semantics: `StrategyContext` and `BarHistory`

```scala
final case class StrategyContext(
  ticker: Ticker,
  history: BarHistory,
  position: Option[OpenPosition],
  portfolio: PortfolioView
)
final case class OpenPosition(ticker: Ticker, quantity: Long, averageCost: BigDecimal, openedOn: LocalDate)
final case class PortfolioView(cash: BigDecimal, equity: BigDecimal)
```

`BarHistory` (`strategy.BarHistory`) wraps `Vector[Bar]`, oldest first, and **always includes the
bar currently being processed** - i.e. inside `onBar(ctx, bar)`, `ctx.history.last.get == bar`:

| Method | Meaning |
|---|---|
| `size` | number of bars in the window |
| `last` | `Option[Bar]`, the most recent bar |
| `lastN(n)` | last `n` bars, chronological, fewer if the history is shorter |
| `closes` | `Vector[BigDecimal]` of close prices |
| `sma(n)`, `ema(n)`, `rsi(n)`, `atr(n)` | `Option[BigDecimal]`, `None` if there isn't enough history for the period - see `strategy.indicators.Indicators` |

Where the history comes from differs by caller:

- **Backtest**: `history` accumulates the *entire* bar list passed to `BacktestEngine.run`, one bar
  at a time, in order - a strategy sees the full run from its start.
- **Live**: `history` is exactly the last `warmupBars` bars from storage on every poll (see above),
  not an ever-growing window - a strategy with `warmupBars = 201` (e.g. a 50/200 MA crossover)
  never sees more than 201 bars live, which is enough for `sma(200)` but not, say, `sma(500)`.

`position` and `portfolio` also differ by caller: the backtest engine tracks a real simulated
position and cash/equity; live evaluation uses a **shadow position** with `portfolio =
PortfolioView(0, 0)` always - see [`alerts.md`](alerts.md#shadow-position) for why trading-pillar
cannot see a real brokerage position.

## Signals, sizing and the fill-at-next-open rule

```scala
sealed trait StrategySignal
object StrategySignal {
  final case class Buy(ticker: Ticker, sizing: Sizing, reason: String) extends StrategySignal
  final case class Sell(ticker: Ticker, sizing: Sizing, reason: String) extends StrategySignal
  final case class Hold(reason: String) extends StrategySignal
}
sealed trait Sizing
object Sizing {
  case object AllIn extends Sizing
  final case class Quantity(n: Long) extends Sizing
  final case class FractionOfEquity(fraction: BigDecimal) extends Sizing
  final case class Notional(amount: BigDecimal) extends Sizing
  case object ClosePosition extends Sizing
}
```

`onBar` returning a `Buy`/`Sell` does **not** fill immediately. A signal produced from bar *t* is
recorded as *pending* and filled at bar *t+1*'s **open**, adjusted by slippage and commission - this
removes look-ahead bias and is the documented contract for both the backtest engine and (by
construction, since the same `onBar` runs live against the latest closed bar) live evaluation. A
signal produced on the very last bar of a backtest run has no next bar to fill against and is
recorded as an unfilled `SkippedSignal` instead. Exact fill math is in
[`backtesting.md`](backtesting.md#execution-fill-at-next-open).

`Sizing` resolves against cash/equity *as of the fill bar's open*, not the signal bar: `AllIn`/
`FractionOfEquity`/`Notional` buys are capped by the largest quantity actually affordable
(price × (1 + commissionRate)); `Sell` quantities are capped at the position's owned quantity. A
resolved quantity of zero (e.g. `FractionOfEquity` too small to buy one share) skips the fill and
records a `SkippedSignal` rather than raising an error.

## Anticipatory signals: `proximity` and `PreSignal`

```scala
final case class PreSignal(side: Side, distanceToTrigger: BigDecimal, reason: String)
sealed trait Side { def name: String }   // "buy" / "sell"
object Side {
  case object Buy extends Side { def name: String = "buy" }
  case object Sell extends Side { def name: String = "sell" }
  def fromName(s: String): Option[Side]  // inverse of `name`; circe's codec is derived from both
}
```

`proximity` is optional (default `None`) and is **never called by the backtest engine** - it only
feeds live "approaching" alerts (`AlertKind.StrategyPreBuy`/`StrategyPreSell`; see
[`alerts.md`](alerts.md)). The `bar` argument may be a *provisional* bar built from the latest live
quote (open = high = low = close = current price, volume 0), dated with the quote's session.
`ctx.history` holds only the sessions before it, even once that session's own bar is stored.
`distanceToTrigger` is a strategy-defined distance, never negative and 0 at the trigger (the
reference crossover strategy uses relative SMA distance, e.g. `0.004` for 0.4%). trading-pillar
passes it through as `Alert.triggerValue` and compares it only with the binding's last warning:
within a session, a warning goes out again once it is closer and at most half as far.

## `StrategyRegistry`

```scala
trait StrategyRegistry[F[_]] {
  def register(strategy: Strategy): F[Unit]   // replaces a duplicate id and logs a warning
  def get(id: StrategyId): F[Option[Strategy]]
  def list: F[List[Strategy]]                 // backs the /strategies command
}
object StrategyRegistryImpl {
  def create[I[_]: Sync, F[_]: Sync](implicit logs: Logs[I, F]): I[StrategyRegistry[F]]
}
```

`StrategyRegistryImpl` is a `Ref`-backed in-memory map; registration is a startup-time, in-process
concern - there is no `StrategyStorage` trait and registered strategies are not persisted (compare
with `StrategyBindingStorage`, which persists *which symbol* a registered strategy id is bound to).

## Reference strategies (examples only)

Both live in `strategy.reference`, are exercised by trading-pillar's own tests, and are **not**
meant to be used unmodified as a real trading strategy - they exist to prove the `Strategy`
contract and to give a backtest/live-alert path something concrete to run against.

### `MovingAverageCrossoverStrategy(fast, slow, proximityThreshold = 0.01)`

- `id = StrategyId(s"ma-cross-$fast-$slow")`, `warmupBars = slow + 1`.
- `onBar`: buys `AllIn` when the fast SMA crosses above the slow SMA and there is no position;
  sells `ClosePosition` on the opposite cross while holding one; otherwise `Hold`.
- `proximity`: compares the SMAs over `ctx.history.closes :+ bar.close`, i.e. with the live bar,
  against the SMAs over the history alone. While flat it warns of a buy when the live bar brings the
  fast SMA nearer the slow one from below, and in a position of a sell when it brings it nearer from
  above, once their relative distance is below `proximityThreshold` (default 1%). Right after a
  crossover the averages move apart, so it stays silent however close they still are.
- Requires `fast > 0`, `slow > 0`, `fast < slow` (constructor `require`s).

### `RsiReversionStrategy(period, lower, upper)`

- `id = StrategyId(s"rsi-reversion-$period-$lower-$upper")`, `warmupBars = period + 2` (needs both
  the current and previous `RSI(period)`, each requiring `period + 1` closes).
- `onBar`: buys `AllIn` when RSI crosses up through `lower` with no position; sells
  `ClosePosition` when RSI crosses down through `upper` while holding one.
- No `proximity` override (inherits the `None` default).
- Requires `period > 0`, `lower < upper`.

## `ParameterizedStrategy`

```scala
trait ParameterizedStrategy extends Strategy {
  def withParameters(params: Map[String, String]): Either[String, Strategy]
}
```

Opt-in capability for a `Strategy` that can rebuild itself from a `Map[String, String]` - the same
representation `Strategy.parameters` and the Telegram layer already use. `withParameters` returns a
**new** `Strategy` (never mutates `this`); every key present in `params` overrides that instance's
current value, and every key *absent* from `params` keeps this instance's own current value, so a
caller only needs to name the parameters it wants to change. `withParameters` never throws:

| Failure | `Left` message |
|---|---|
| An unknown key in `params` | `"Unknown parameter(s): <key>[, <key>...]"` (unknown keys sorted, comma-joined) |
| A value that doesn't parse (wrong type for that key) | `"Invalid value for <key>: <value>"` |
| A combination that violates the strategy's own constructor invariants | that invariant's own `require` message (e.g. `"fast must be less than slow"`) - the candidate instance is actually constructed and a failed `require` is caught and turned into a `Left`, rather than the invariant being re-implemented in `withParameters` |

Both reference strategies implement it:

| Strategy | Keys | Value type |
|---|---|---|
| `MovingAverageCrossoverStrategy` | `fast`, `slow`, `proximityThreshold` | `fast`/`slow`: `Int`; `proximityThreshold`: `BigDecimal` |
| `RsiReversionStrategy` | `period`, `lower`, `upper` | `period`: `Int`; `lower`/`upper`: `BigDecimal` |

```scala
val base = new MovingAverageCrossoverStrategy(fast = 50, slow = 200)
base.withParameters(Map("fast" -> "20"))
// Right(a new MovingAverageCrossoverStrategy(fast = 20, slow = 200, proximityThreshold = base's current one))
//   -> id = StrategyId("ma-cross-20-200")
base.withParameters(Map("fast" -> "300"))
// Left("fast must be less than slow")
base.withParameters(Map("bogus" -> "1"))
// Left("Unknown parameter(s): bogus")
```

Note that the rebuilt strategy's `id`/`name`/`parameters` reflect the **new** values (since they're
computed from the constructor arguments, same as any other instance) - this is what lets a reply
built from the resulting `BacktestResult` show which parameters actually ran without any extra
bookkeeping. Consumers: `/backtest`'s `params=` token (one ad hoc run, see
[`telegram-commands.md`](telegram-commands.md#backtest)) and `BacktestSweep`/`/sweep` (a whole grid
of parameter combinations, see
[`backtesting.md`](backtesting.md#parameter-sweeps-backtestsweep)).

## Indicators

`strategy.indicators.Indicators` - pure functions over `Vector[BigDecimal]`/`Vector[Bar]`, `None`
whenever there isn't enough history for the period. Every call recomputes from the start of the
given series, so passing the full accumulated history (as `StrategyContext.history` does) gives
values consistent with an incremental computation.

| Function | Method |
|---|---|
| `sma(closes, n)` | simple mean of the last `n` closes |
| `ema(closes, n)` | seeded with `sma` of the first `n` closes, then smoothed forward (`alpha = 2/(n+1)`) |
| `rsi(closes, n)` | Wilder's RSI: gains/losses seeded as a simple average of the first `n` changes, then Wilder-smoothed; needs `n+1` closes |
| `atr(bars, n)` | Wilder's ATR: true range seeded as high−low for the first bar (no previous close), `max(high−low, \|high−prevClose\|, \|low−prevClose\|)` afterwards, then Wilder-smoothed over `n` bars |

`divide(a, b)` (also public) divides two `BigDecimal`s with `MathContext.DECIMAL64`, used
throughout the engine and metrics to avoid `ArithmeticException` on non-terminating decimals.
