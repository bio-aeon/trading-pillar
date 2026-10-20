# Backtesting

Package `st.btu.trading.pillar.strategy.backtest`. The engine is pure and deterministic
(`Strategy => List[Bar] => BacktestResult`, no `F[_]`); `BacktestService` adds storage I/O around
it. A multi-ticker/portfolio variant (`BacktestEngine.runPortfolio`, one shared cash balance) is
documented [below](#multi-ticker-backtests-backtestenginerunportfolio).

## `BacktestConfig`

```scala
final case class BacktestConfig(initialCash: BigDecimal, commissionRate: BigDecimal, slippageBps: Int)
```

`commissionRate` is a fraction of notional (e.g. `0.0005` = 5 bps) applied to both buys and sells.
`slippageBps` worsens the fill price (see below). `BacktestConfig` has no defaults; the README quick
start uses `initialCash = 100000`, `commissionRate = 0.0005` and `slippageBps = 5`.

## `BacktestEngine.run`

```scala
object BacktestEngine {
  def run(strategy: Strategy, bars: List[Bar], config: BacktestConfig): Either[BacktestError, BacktestResult]
}
```

### Validation (before any simulation)

| `BacktestError` | When |
|---|---|
| `NoBars` | `bars.isEmpty` |
| `InvalidBars(message)` | `bars` is not strictly chronological with unique dates (`sliding(2)` pairwise `isBefore` check) |
| `NotEnoughBars(required, actual)` | `bars.size < strategy.warmupBars` |
| `InvalidStrategy(message)` | Not raised by `BacktestEngine.run` itself - `BacktestSweep` uses this case to carry a `ParameterizedStrategy.withParameters` failure for one grid point (see [below](#parameter-sweeps-backtestsweep)); `message` is that failure's own `Left` message, unchanged. |

### Simulation loop

The engine folds over `bars` once, in order. For each bar, in this exact sequence:

1. **Fill pending orders** - any `StrategySignal`s produced by the *previous* bar's `onBar` call are
   filled at **this bar's `open`** (see [Execution](#execution-fill-at-next-open) below). This is
   step 1 specifically so a signal from bar *t* never fills using bar *t*'s own open.
2. **Mark equity** - `equity = cashAfterFill + positionQuantity × thisBar.close`; appended to the
   equity curve as an `EquityPoint(date, equity, cash, quantity)`.
3. **Ask the strategy** - once `history.size >= strategy.warmupBars` (history now includes this
   bar), `strategy.onBar(ctx, bar)` runs with `ctx.position`/`ctx.portfolio` reflecting the fill and
   equity just computed. Its returned signals become *pending* for the next iteration - **except**
   on the last bar of the series, where they have no next bar to fill against and are instead
   recorded as `SkippedSignal(date, "Unfilled at end of backtest: <reason>")`.

`proximity` is never called by the engine (backtest-only concept, see
[`strategies.md`](strategies.md#anticipatory-signals-proximity-and-presignal)).

### Execution: fill-at-next-open

```scala
buyFillPrice  = open * (1 + slippageBps / 10000)
sellFillPrice = open * (1 - slippageBps / 10000)
commission    = commissionRate * (quantity * fillPrice)
```

- **Buy**: quantity is `min(sizing-resolved quantity, maxAffordableQty)`, where
  `maxAffordableQty = floor(cash / (fillPrice * (1 + commissionRate)))`. `AllIn` requests the max
  directly; `Quantity(n)` requests `n`; `FractionOfEquity(f)`/`Notional(amount)` convert to a
  quantity via `floor(notional / fillPrice)`; `ClosePosition` resolves to 0 for a buy. A resolved
  quantity `<= 0` skips the fill (`SkippedSignal`, not an error) - cash and position are unchanged.
  A new position's `averageCost` is the fill price; adding to an existing position recomputes a
  weighted-average cost.
- **Sell**: quantity is `min(sizing-resolved quantity, position.quantity)`; `AllIn` and
  `ClosePosition` both resolve to the full owned quantity. A `Sell` with no open position, or a
  resolved quantity `<= 0`, skips the fill (`SkippedSignal`). Selling the full quantity closes the
  position (`None`); a partial sell reduces `quantity` and keeps the original `averageCost`.
- **Hold**: no-op.

Every actual fill (buy or sell) is appended to `trades: List[Trade]`:

```scala
final case class Trade(date: LocalDate, side: Side, quantity: Long, price: BigDecimal, commission: BigDecimal, reason: String)
```

## Metrics

`strategy.backtest.Metrics.compute(initialCash, equityCurve, trades, firstOpen, lastClose):
BacktestMetrics`, called once at the end of `simulate` with the *first* bar's `open` and the
*last* bar's `close`.

```scala
final case class BacktestMetrics(
  totalReturn: BigDecimal,
  annualizedReturn: BigDecimal,
  maxDrawdown: BigDecimal,
  sharpeAnnualized: BigDecimal,
  winRate: Option[BigDecimal],
  tradeCount: Int,
  roundTrips: Int,
  finalEquity: BigDecimal,
  buyAndHoldReturn: BigDecimal
)
```

| Metric | Exact definition |
|---|---|
| `finalEquity` | Last `EquityPoint.equity`, or `initialCash` if the curve is empty. |
| `totalReturn` | `finalEquity / initialCash - 1`; `0` if `initialCash <= 0` (no meaningful return to report). |
| `annualizedReturn` | `ratio^(365 / calendarDays) - 1` where `ratio = finalEquity/initialCash` and `calendarDays = ChronoUnit.DAYS.between(firstPoint.date, lastPoint.date)`; falls back to `totalReturn` unchanged if `calendarDays < 1`. |
| `maxDrawdown` | Largest peak-to-trough fraction over the equity curve: running `peak = max(peak, equity)`, `drawdown = (peak - equity) / peak` (0 while `peak <= 0`), `maxDrawdown = max` of all drawdowns seen. Always ≥ 0. |
| `sharpeAnnualized` | Over per-point returns `(equity[i] - equity[i-1]) / equity[i-1]`, skipping any pair whose prior point's equity is `<= 0` (not folded in as a 0% return): `mean / sampleStdev(ddof=1) * sqrt(252)`, rounded to scale 8. `0` if fewer than 2 (remaining) returns or the sample stdev is `0`. |
| `winRate` | Over completed round trips (see below): fraction with positive PnL. `None` if there are no round trips (not `Some(0)`). |
| `tradeCount` | `trades.size` - every individual fill, buy or sell. |
| `roundTrips` | Number of completed round trips (see below). *Not currently exposed by any Telegram command.* |
| `buyAndHoldReturn` | `lastClose / firstOpen - 1` - note this compares the **first bar's open** to the **last bar's close**, not close-to-close; `0` if `firstOpen <= 0`. |

**Round trip**: a round trip starts when a flat position (quantity 0) receives a buy and ends when
the running quantity returns to 0 (one or more sells). Its PnL is
`totalSellNotional - totalBuyNotional - sum(commissions)` accumulated over every trade in the round
trip. A position that is still open at the end of the backtest is **not** counted as a round trip
(and therefore doesn't affect `winRate`).

## `BacktestResult` / `BacktestRun`

```scala
final case class BacktestResult(
  strategyId: StrategyId,
  ticker: Ticker,
  parameters: Map[String, String],   // Strategy.parameters, snapshotted at run time
  dateFrom: LocalDate,               // first bar's date
  dateTo: LocalDate,                 // last bar's date
  config: BacktestConfig,
  trades: List[Trade],
  equityCurve: List[EquityPoint],
  metrics: BacktestMetrics,
  skipped: List[SkippedSignal]       // buys/sells the engine could not fill, with a reason
)
final case class BacktestRun(id: BacktestRunId, createdAt: ZonedDateTime, result: BacktestResult)
```

## Multi-ticker backtests (`BacktestEngine.runPortfolio`)

```scala
object BacktestEngine {
  def runPortfolio(strategy: Strategy, barsByTicker: Map[Ticker, List[Bar]], config: BacktestConfig): Either[BacktestError, PortfolioBacktestResult]
}
final case class PortfolioBacktestResult(
  strategyId: StrategyId,
  tickers: List[Ticker],              // sorted alphabetically
  parameters: Map[String, String],
  dateFrom: LocalDate,                // earliest date across every ticker's own bars
  dateTo: LocalDate,                  // latest date across every ticker's own bars
  config: BacktestConfig,
  tradesByTicker: Map[Ticker, List[Trade]],
  equityCurve: List[EquityPoint],     // one shared portfolio total per date
  metrics: BacktestMetrics,
  skipped: List[SkippedSignal]
)
```

Runs the same `strategy` independently against every ticker's own bar history, but with **one
shared cash balance** instead of a separate account per ticker. Dates are merged across every
ticker (the union of each ticker's own bar dates, sorted); on each merged date, tickers are
processed **in alphabetical order** (by `Ticker.value`), and a ticker with no bar on that date is
skipped entirely for it (no fill, no new signal; its position's mark-to-market carries over at its
last known close) - that does not stall other tickers' processing or its own next real bar's fill
timing.

Per ticker, per date, the same rules as `run` apply: fill-at-next-open (using that ticker's own
`open`), the same commission/slippage math, long-only positions (a `Sell` can never go short -
unchanged from `run`), and the same per-ticker warmup/last-bar handling (a ticker's signals become
unfillable only on *its own* last bar, independent of other tickers' date ranges). The one
difference is **sizing**: `Sizing.FractionOfEquity`/`Notional` resolve against the *whole
portfolio's* equity (shared cash, plus every ticker's current position valued at that date's own
open-or-last-close), not just the ticker being filled - two tickers each sized at
`FractionOfEquity(0.5)` split one account down the middle instead of each claiming half of a
separate pot. `Sizing.AllIn` still resolves against `cash` alone (you can only spend what's
actually left), which is what makes shared cash observable: if the alphabetically-first ticker
takes an all-in buy, a second ticker's own all-in buy on the same date resolves to zero (a
`SkippedSignal`, the same "Buy resolved to zero quantity" reason `run` uses) because there is no
cash left.

`equityCurve` is one combined series: `EquityPoint(date, equity, cash, quantity)` where `equity` is
cash plus every ticker's position valued at that date's close (or last known close for a ticker
with no bar that date), `cash` is the single shared balance, and `quantity` is the sum of every
open position's quantity (a rough aggregate across tickers with potentially different prices, not
a meaningful share count on its own). `metrics` is `Metrics.compute` over this combined curve
(`totalReturn`, `annualizedReturn`, `maxDrawdown`, `sharpeAnnualized`, `finalEquity` - identical
definitions to `run`'s, see [above](#metrics)), except:

- **`winRate`/`roundTrips`/`tradeCount`**: round trips are detected **per ticker** (the same
  internal round-trip logic `run` uses) and only the resulting PnLs are combined - a buy on one
  ticker and a sell on another never net together into one "position returning to flat". `tradeCount`
  is the sum of every ticker's own trade count.
- **`buyAndHoldReturn`**: an equal-weight buy-and-hold across the whole portfolio - split
  `config.initialCash` evenly across every ticker, "buy" each one at its own first bar's open, value
  the result at its own last bar's close, and compare the summed final value against `initialCash`
  (`Performance.totalReturn`). This is a different basis than `run`'s single-symbol
  `lastClose/firstOpen - 1`, by construction (there is no single symbol to buy and hold).

A single-ticker call (`barsByTicker` with exactly one entry) produces byte-identical `trades`,
`equityCurve` and `metrics` to calling `run` with that same ticker's bars directly - `runPortfolio`
reuses `run`'s own internal fill/validation functions rather than re-implementing them (see
`BacktestEngineSpec`'s dedicated equivalence spec).

Validation mirrors `run`'s, applied to each ticker's own bars in alphabetical order (the first
ticker that fails determines the error): `NoBars` if `barsByTicker` is empty, otherwise the usual
`NoBars`/`InvalidBars`/`NotEnoughBars` per ticker.

### `BacktestService.runPortfolio`

```scala
trait BacktestService[F[_]] {
  def runPortfolio(strategyId: StrategyId, tickers: List[SourcedTicker], from: LocalDate, to: LocalDate, config: BacktestConfig): F[BacktestRun]
}
```

Looks up `strategyId` in the `StrategyRegistry` (raising `StrategyNotFound`, same as `run`), loads
every ticker's bars from `PriceHistoryStorage.bars(ticker, source, from, to)` using the source
**paired with that ticker** in its `SourcedTicker` (so one portfolio can mix sources, and bars
another source stored for the same ticker are never read), runs `BacktestEngine.runPortfolio`, and
persists the result - but since `BacktestRunStorage` only knows how to save a single-ticker-shaped
`BacktestResult`, the `PortfolioBacktestResult` is first projected into one: `ticker` becomes every
ticker's `value` joined by `","` (e.g. `"AAPL,MSFT,TSLA"`), and `trades` becomes every ticker's own
trade list concatenated **in ticker order** and then sorted by date (a stable sort, so same-date
trades from different tickers keep that deterministic ticker-alphabetical order) - `equityCurve`,
`metrics`, `skipped`, `parameters`, `dateFrom`/`dateTo` and `config` carry over unchanged. The
sources are not part of the persisted run. This needs no `BacktestRunStorage` schema or interface
change. There is no `runStrategy`-style ad hoc/`params=`
counterpart for portfolios - see [`telegram-commands.md`](telegram-commands.md#backtest).

## Equity-curve images (`charts.EquityCurveImage`, `TgGateway.sendPhoto`)

```scala
object EquityCurveImage {
  def render(points: List[EquityPoint], width: Int = 900, height: Int = 400): Array[Byte]   // PNG bytes
}
```

A minimal, dependency-free PNG line chart of an equity curve: `java.awt.image.BufferedImage` +
`javax.imageio.ImageIO`, with `java.awt.headless` set to `true` by the object itself (so no
`-Djava.awt.headless` JVM flag is needed at the call site - a bot process has no display anyway).
Draws an axes frame, the equity curve as a single line, the min/max equity values near the y-axis
and the first/last point's dates near the x-axis; a flat curve (`max == min`) renders a centered
horizontal line instead of dividing by zero, and an empty or single-point series still renders a
valid (if content-free) PNG. Pure function, no `F[_]`, no persisted state - errors are the caller's
concern (see below).

`TgGateway.sendPhoto(chatId, filename, content: Array[Byte], caption: Option[String]): F[Unit]` is
the multipart-upload counterpart to
[`sendDocument`](telegram-commands.md#tggatewaysenddocument): the same POST-multipart shape
(`chat_id`, the file part, an optional `caption`), except the field name is `photo` (not
`document`), the content type is always `image/png` (not a caller-supplied `mimeType`), and
Telegram renders it inline in the chat instead of offering it as a file attachment.

`/backtest` (both single- and multi-ticker) sends the run's equity curve as an image immediately
after its text reply, whenever the run has **at least 2** `EquityPoint`s - a 0- or 1-point curve
(possible only for a backtest with almost no bars) is skipped rather than rendering a degenerate
image. Both rendering (`EquityCurveImage.render`) and sending (`sendPhoto`) are wrapped in one
error handler: any failure in either step is logged and swallowed, never propagated - a broken
chart can never turn an otherwise-successful `/backtest` into a failed command (no "Command failed:
..." reply), and the text reply has already been sent by the time the image is attempted.

## `BacktestService`

```scala
trait BacktestService[F[_]] {
  def run(strategyId: StrategyId, ticker: Ticker, source: Source, from: LocalDate, to: LocalDate, config: BacktestConfig): F[BacktestRun]
  def runStrategy(strategy: Strategy, ticker: Ticker, source: Source, from: LocalDate, to: LocalDate, config: BacktestConfig): F[BacktestRun]
}
sealed abstract class BacktestServiceError(msg: String) extends Exception(msg)
object BacktestServiceError {
  final case class StrategyNotFound(id: StrategyId) extends BacktestServiceError(...)
  final case class Failed(error: BacktestError) extends BacktestServiceError(error.message)
}
```

`BacktestServiceImpl.run`: looks up the strategy in `StrategyRegistry` (raising
`StrategyNotFound` if unregistered), loads `PriceHistoryStorage.bars(ticker, source, from, to)`,
runs `BacktestEngine.run` (raising `Failed(error)` on the left case), then
`BacktestRunStorage.save(result, now)` and returns the persisted `BacktestRun`. `runStrategy` is
the same pipeline minus the registry lookup, for an ad hoc `Strategy` instance the caller already
has in hand - it can never raise `StrategyNotFound`. The `/backtest` Telegram command (see
[`telegram-commands.md`](telegram-commands.md#backtest)) calls `run` normally and `runStrategy` for
its `params=` token (a registered `ParameterizedStrategy` rebuilt with overridden parameters for
one ad hoc run, still persisted exactly like any other run); both handlers map `StrategyNotFound`
and `Failed(NotEnoughBars)` to a user-facing reply, same as before.

```scala
trait BacktestRunStorage[F[_]] {
  def save(result: BacktestResult, createdAt: ZonedDateTime): F[BacktestRunId]
  def get(id: BacktestRunId): F[Option[BacktestRun]]
  def recent(limit: Int): F[List[BacktestRun]]
}
```

## Parameter sweeps (`BacktestSweep`)

Package `st.btu.trading.pillar.strategy.backtest`, same as the engine. Runs one
`ParameterizedStrategy` across a grid of parameter combinations, in parallel, over a single fixed
bar series - "which (fast, slow) pair performed best over the last two years", not multiple
periods (that's [walk-forward](#walk-forward-evaluation-walkforward), below).

```scala
final case class SweepPoint(params: Map[String, String], result: Either[BacktestError, BacktestResult])

object BacktestSweep {
  def run[F[_]: Async](
    base: ParameterizedStrategy,
    grid: List[Map[String, String]],
    bars: List[Bar],
    config: BacktestConfig,
    parallelism: Int = 4
  ): F[List[SweepPoint]]

  def rank(points: List[SweepPoint], by: BacktestMetrics => BigDecimal): List[SweepPoint]
}
```

`run` builds `base.withParameters(params)` for every `Map[String, String]` in `grid` and, on a
`Right`, runs `BacktestEngine.run` against the same `bars`/`config` for all of them; a
`withParameters` `Left` becomes `SweepPoint(params, Left(BacktestError.InvalidStrategy(msg)))`
instead of failing the whole sweep - one bad grid point (an unknown key, an unparsable value, or a
combination that violates the strategy's own invariants, e.g. `fast >= slow`) just becomes a failed
point, same as any `BacktestEngine.run` failure (most commonly `NotEnoughBars`, when a point's
`warmupBars` exceeds `bars.size`). Up to `parallelism` points run concurrently
(`List.parTraverseN`); `grid` order is otherwise irrelevant since every point is independent and
`SweepPoint` always carries its own `params` back.

`rank` keeps only the successful points (`SweepPoint(_, Right(_))`), sorted by `by(result.metrics)`
descending - the caller picks the metric (`/sweep` uses `_.sharpeAnnualized` for its "top 5 by
Sharpe"; nothing stops a caller from ranking by `_.totalReturn` or any other `BacktestMetrics`
field instead).

### `Grid.expand`

```scala
object Grid {
  val MaxCombinations = 100
  def expand(spec: Map[String, (BigDecimal, BigDecimal, BigDecimal)]): Either[String, List[Map[String, String]]]
}
```

Pure expansion of a `key -> (from, to, step)` specification (one inclusive range per parameter key)
into the concrete `grid: List[Map[String, String]]` `BacktestSweep.run` consumes - the cartesian
product across keys, keys expanded in alphabetical order so the result is deterministic regardless
of the input `Map`'s own iteration order (e.g. `{fast: (10,20,10), slow: (50,60,10)}` expands
fast-major/slow-minor: `[{fast:10,slow:50}, {fast:10,slow:60}, {fast:20,slow:50},
{fast:20,slow:60}]`). A value that is mathematically a whole number renders without a decimal point
(`BigDecimal#isWhole`, e.g. `"50"` not `"50.0"`, even if `from`/`to`/`step` were written with
trailing zeros); any other value renders with `BigDecimal#toString`. `step` must be positive for
every key (`Left` otherwise - this also guards against an infinite expansion). The total number of
combinations (the product of every key's value count) is capped at `MaxCombinations` (100): beyond
that, `Left(s"Grid too large: $total combinations (max 100)")` instead of silently truncating or
running a possibly very slow sweep - a request this large is almost always a typo (e.g. a `step` of
`0.01` over a range meant in whole units).

## Walk-forward evaluation (`WalkForward`)

Package `st.btu.trading.pillar.strategy.backtest`. Rolling-window out-of-sample evaluation: fit (or
just pick) parameters on a training slice, then measure performance on the slice immediately after
it, repeated across the whole series - a defense against overfitting a single backtest window.
trading-pillar's strategies have no fitting step of their own (a caller chooses parameters, e.g. via
[`BacktestSweep`](#parameter-sweeps-backtestsweep) run against each window's own `train` bars), so
`WalkForward` only supplies the windowing and the train/test isolation; it does not pick parameters
for you.

```scala
final case class Window(train: List[Bar], test: List[Bar])

final case class WalkForwardSummary(
  windows: Int,
  meanTestReturn: BigDecimal,
  worstTestReturn: BigDecimal,
  consistency: BigDecimal
)

object WalkForward {
  def split(bars: List[Bar], trainDays: Int, testDays: Int): List[Window]
  def evaluate(strategy: Strategy, windows: List[Window], config: BacktestConfig): List[Either[BacktestError, BacktestResult]]
  def summary(results: List[Either[BacktestError, BacktestResult]]): WalkForwardSummary
}
```

### `split`

Splits `bars` into consecutive `Window`s measured in **calendar days**, not bar counts (`bars` need
not be pre-sorted - `split` sorts a copy first). Window `i` starts `i * testDays` calendar days
after the first bar's date; `train` covers the following `trainDays` days and `test` the
`testDays` days immediately after that, so `train` and `test` are always adjacent and
non-overlapping. Windows roll forward by `testDays` each step (not `trainDays`), so consecutive
*test* windows are contiguous and non-overlapping while consecutive *train* windows overlap
whenever `trainDays > testDays` (the usual case) - this rolling-start behavior is what distinguishes
it from one anchored/expanding train window. The last candidate window is **dropped, not
truncated**, if its `test` period's calendar span isn't entirely covered by `bars` (i.e. there is no
partial final test window): e.g. with `trainDays = 4, testDays = 2` a 10-day series produces 3 full
windows, but a 9-day series produces only 2 (the third window's 2-day test period would only have 1
day of real data). `trainDays`/`testDays` must both be positive, or the result is empty; a `train`
or `test` slice can still be shorter than its nominal day count (or even empty) when `bars` has
gaps, e.g. weekends/holidays with no trading bar on some of those calendar days.

### `evaluate`

Runs `strategy` **once per window**, over `window.train ++ window.test` - so `onBar` sees the train
bars as warm-up history before the test window begins, exactly as it would with that much more
real history, letting indicators (SMAs, RSI, ATR, ...) and any open position reach the test window
already primed instead of cold-starting at its first bar. The raw `BacktestResult` this produces
covers the *whole* `train ++ test` run, which is not what a walk-forward summary wants (it would
dilute - or hide - the test window's own performance inside the train period's); `evaluate` isolates
the test window from it:

1. **Equity curve**: keep only the points dated on or after `test`'s first bar, then shift every
   one of them by the same constant `delta = config.initialCash - equityAtFirstTestPoint`, applied
   to both `equity` and `cash`. This makes the first test point read `initialCash` exactly while
   preserving every later point's *change* from it bar to bar - so the rebased curve shows exactly
   the P&L the position (whatever it was by the end of training) generated **during the test
   window**, as if the account had been recapitalized to `initialCash` at the test start without
   touching the position itself. `cash` moves only when a real trade happens in the test window
   (just like in any other result) - if none does, the rebased `cash` is the same constant
   `delta` at every point, and the entire P&L shows up in `equity` via the held position's
   mark-to-market.
2. **Trades and skipped signals**: keep only those dated on or after `test`'s first bar - a trade
   that happened during training (most commonly the position's own entry) is warm-up, not part of
   the test window's own performance, and is dropped here even though it's what makes the position
   exist in the first place.
3. **Metrics**: `Metrics.compute` recomputed from scratch on the rebased equity curve and the
   test-only trades, with `firstOpen`/`lastClose` taken from `test`'s own first/last bar (so
   `buyAndHoldReturn` compares against the test window alone, not the combined series).

A window whose `train ++ test` combination fails `BacktestEngine.run`'s own validation (most
commonly `NotEnoughBars`, when `train` has fewer bars than `strategy.warmupBars`) surfaces as a
`Left` for that window; one window's failure doesn't stop the others. A window with an empty `test`
side (possible only if `bars` has a calendar gap exactly there) fails with `BacktestError.NoBars`
without calling `BacktestEngine.run` at all.

### `summary`

```scala
WalkForwardSummary(windows: Int, meanTestReturn: BigDecimal, worstTestReturn: BigDecimal, consistency: BigDecimal)
```

`windows` is `results.size` (every window passed in, successful or not). The other three fields are
computed over the **successful** windows' `metrics.totalReturn` only - a failed window is excluded
from both the numerator and the denominator everywhere, since a failure isn't a "non-positive
return", it's the absence of one. `meanTestReturn` and `worstTestReturn` are the mean and the
minimum of those returns; `consistency` is the share of them that are strictly positive
(`count(_ > 0) / count`). All three are `0` when there are no successful windows (including an empty
`results`).

## Persistence and JSON layout

trading-pillar defines the storage *trait* only; `BacktestRunStorage` has no built-in JSON codec
requirement of its own beyond the circe codecs derived for `BacktestConfig`/`BacktestMetrics`/
`Trade`/`EquityPoint` (all in `strategy.backtest`) and circe's generic derivation for
`Map[String, String]`/`List[Trade]`/`List[EquityPoint]`. A relational implementation (suggested
table `backtest_runs`, see [`storage-contracts.md`](storage-contracts.md#backtestrunstoragef_))
could store five columns as circe `.asJson.noSpaces` text:

| Column | Encoded from |
|---|---|
| `parameters` | `result.parameters: Map[String, String]` |
| `config` | `result.config: BacktestConfig` |
| `metrics` | `result.metrics: BacktestMetrics` |
| `trade_log` | `result.trades: List[Trade]` |
| `equity_curve` | `result.equityCurve: List[EquityPoint]` |

`strategy_id`, `symbol`, `date_from`, `date_to` and `created_at` could be plain columns, not JSON.
`skipped` need not be stored at all: an implementation that leaves it out rebuilds `BacktestResult`
with `skipped = Nil` in `get`/`recent`, so a consumer cannot expect skipped signals to survive a
round trip through it. (`SkippedSignal` also has a circe codec, for an implementation that does
store them.) A corrupt JSON value should fail the decode of the whole row, naming the row id,
rather than silently dropping a field.

Each value is encoded and decoded as one whole circe document, so the store only needs to hold JSON
text or a JSON column.
