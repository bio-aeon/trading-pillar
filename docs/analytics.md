# Analytics

Package `st.btu.trading.pillar.analytics`. Pure return/risk statistics over a value or return
series - no `F[_]`, no I/O. Used by `strategy.backtest.Metrics` (see below) and by the portfolio
valuation pipeline (`analytics.portfolio` - see below).

**BigDecimal conventions** (matching `strategy.indicators.Indicators`): every division goes
through `Indicators.divide` (`MathContext.DECIMAL64`, 16 significant digits). A result that passes
through `Double` on its way back to `BigDecimal` (`math.pow`, `math.sqrt`, `math.log`) is rounded
to scale 8 with `HALF_UP`, so it doesn't carry fake precision from the double's binary expansion. A
result that stays pure `BigDecimal` throughout (`totalReturn`, `timeWeighted`, `maxDrawdown`,
`valueAtRisk`, `beta`) is left at whatever precision `divide` produced - this matches the
pre-existing convention in `strategy.backtest.Metrics`, where `totalReturn`/`maxDrawdown` are
likewise unrounded and only the double-derived `sharpeAnnualized` is rounded to scale 8.

## `Returns`

```scala
object Returns {
  def simple(values: Vector[BigDecimal]): Vector[BigDecimal]
  def log(values: Vector[BigDecimal]): Vector[BigDecimal]
}
```

| Function | Definition |
|---|---|
| `simple` | `v(t) / v(t-1) - 1` for each consecutive pair. |
| `log` | `ln(v(t) / v(t-1))` for each consecutive pair - via `Double` (`math.log`; there is no BigDecimal `ln`), rounded to scale 8. |

Both are empty for fewer than two input values (nothing to difference against), and both skip a
pair whose previous value is `<= 0` - the ratio is undefined or sign-flipped, so there is no
meaningful return to report for that pair.

## `Valuation`

```scala
final case class Valuation(date: LocalDate, value: BigDecimal, externalFlow: BigDecimal)
```

One portfolio observation: `value` on `date`, and `externalFlow` - the net deposit (positive) or
withdrawal (negative) booked on that date. A flow is assumed to land at the **start** of the day,
before that day's return is realized; both `Performance.timeWeighted` and `Performance.moneyWeighted`
rely on this. A `Vector[Valuation]` must already be in chronological order - nothing in this
package sorts it.

## `Performance`

```scala
object Performance {
  def totalReturn(first: BigDecimal, last: BigDecimal): BigDecimal
  def annualized(total: BigDecimal, calendarDays: Long): BigDecimal
  def timeWeighted(valuations: Vector[Valuation]): BigDecimal
  def moneyWeighted(valuations: Vector[Valuation]): Option[BigDecimal]
}
```

| Function | Definition |
|---|---|
| `totalReturn` | `last / first - 1`; `0` when `first <= 0` (no meaningful return to report). |
| `annualized` | `(1 + total)^(365 / calendarDays) - 1`; returns `total` unchanged when `calendarDays < 1` (too short a span to annualize). Runs through `Double` (`math.pow`), rounded to scale 8. |
| `timeWeighted` | Chains sub-period returns `value(t) / (value(t-1) + flow(t)) - 1` - compounds `(1 + r)` across every period, `- 1` at the end. A period whose denominator `value(t-1) + flow(t) <= 0` (e.g. a withdrawal larger than the prior value) is **skipped**, not folded in as a 0% or negative-base return. `0` for fewer than two valuations. |
| `moneyWeighted` | Annualized XIRR of the valuation series' cash flows - see below. |

Worked example (also a spec KAT): valuations `100 → 110 → 176` with a `+50` deposit landing on the
third date. Period 1: `110 / (100 + 0) - 1 = 10%`. Period 2: `176 / (110 + 50) - 1 = 10%`. TWR
`= 1.10 × 1.10 - 1 = 21%`.

### `moneyWeighted` / XIRR

The cash-flow series handed to the solver, from the investor's point of view:

| Date | Amount |
|---|---|
| `valuations.head.date` | `-valuations.head.value` (paying in to open the position) |
| every date strictly between first and last | `-externalFlow(t)` (a deposit is money paid in, so it's negated to a cash outflow; a withdrawal is money received back, so it becomes positive - the mirror image of `Valuation`'s own sign convention) |
| `valuations.last.date` | `+valuations.last.value` (the value received by closing the position out) |

Only the first/last valuations' `value` feeds into the series (their own `externalFlow`, if any, is
ignored); only the middle valuations' `externalFlow` does (their `value` is ignored). With exactly
two valuations there are no middle dates, so the series is simply `[-value(first), +value(last)]`
(the two-flow KAT: `-100` then `+110` exactly a year later → `10%`).

`NPV(rate) = Σ amount / (1 + rate)^years`, with `years` measured ACT/365 from the first date (the
first flow's own `years` is always `0`, so it's never discounted). Solved by bisection over
`rate ∈ (-0.99, 10)` until the bracket narrows below `1e-8` (capped at 200 iterations), then rounded
to scale 8. `None` when there are fewer than two valuations, or `NPV(-0.99)` and `NPV(10)` don't
straddle zero - e.g. a series with no negative flow at all, which is positive at every rate in the
interval and so has nothing for bisection to converge to.

## `Risk`

```scala
object Risk {
  def volatility(returns: Vector[BigDecimal], periodsPerYear: Int = 252): BigDecimal
  def sharpe(returns: Vector[BigDecimal], riskFreePerPeriod: BigDecimal = 0, periodsPerYear: Int = 252): BigDecimal
  def sortino(returns: Vector[BigDecimal], riskFreePerPeriod: BigDecimal = 0, periodsPerYear: Int = 252): BigDecimal
  def maxDrawdown(values: Vector[BigDecimal]): BigDecimal
  def valueAtRisk(returns: Vector[BigDecimal], confidence: BigDecimal): BigDecimal
  def beta(returns: Vector[BigDecimal], benchmark: Vector[BigDecimal]): Option[BigDecimal]
}
```

| Function | Definition |
|---|---|
| `volatility` | Sample stdev (ddof=1) of `returns`, annualized by `× sqrt(periodsPerYear)`. `0` for fewer than two returns. |
| `sharpe` | `mean(excess) / stdev(excess) × sqrt(periodsPerYear)`, where `excess = returns - riskFreePerPeriod`, stdev sample-based (ddof=1). `0` when the excess stdev is `0` (e.g. a flat series) or fewer than two returns. |
| `sortino` | Like `sharpe`, but the denominator is the **downside deviation**: the root-mean-square of the negative excess returns only, divided by their own count (not `count - 1`) since it measures deviation from the fixed target `0`, not from an estimated mean. `0` when fewer than two returns, no excess return is negative (no downside observed), or the downside deviation is `0`. |
| `maxDrawdown` | Largest peak-to-trough fraction of `values`: running `peak = max(peak, v)`, `drawdown = (peak - v) / peak` (`0` while `peak <= 0`), `maxDrawdown` = the largest drawdown seen. `0` for an empty series. Always ≥ 0. |
| `valueAtRisk` | Historical/empirical VaR: sort `returns` ascending and take the value at the nearest-rank index for the `(1 - confidence)` quantile (`index = ceil((1 - confidence) × n) - 1`, clamped to `[0, n-1]`), negated so a loss reads as a positive number. `0` for an empty series. |
| `beta` | `covariance(returns, benchmark) / variance(benchmark)`, both sample-based (ddof=1), over the aligned **prefix** of equal length (`returns.take(n)` / `benchmark.take(n)`, `n = min(sizes)` - the two series are assumed to already be period-aligned, not aligned by date here). `None` when that variance is `0` (e.g. a flat benchmark) or fewer than two aligned pairs. |

**Decision flagged for review**: `sortino`'s downside-deviation convention (population divisor,
deviation measured from the fixed target `riskFreePerPeriod` rather than from the downside
subset's own mean) is the standard finance-literature definition (Sortino & van der Meer), and the
most natural fit for the terse specification ("downside deviation over negative excess returns
only"), but it was an implementation judgment call, not a dictated formula - flag it if a different
convention (sample ddof=1, or deviation from the downside subset's own mean) is wanted instead.

## Relationship to `strategy.backtest.Metrics`

`Metrics.compute` delegates to this package wherever the definition is identical:

| `BacktestMetrics` field | Delegates to |
|---|---|
| `totalReturn` | `Performance.totalReturn(initialCash, finalEquity)` |
| `annualizedReturn` | `Performance.annualized(totalReturn, calendarDays)` |
| `maxDrawdown` | `Risk.maxDrawdown(equityCurve.map(_.equity).toVector)` |
| `sharpeAnnualized` | `Risk.sharpe(Returns.simple(equityCurve.map(_.equity).toVector))` - `Returns.simple` already skips a pair whose previous equity is `<= 0`, so `Metrics` no longer needs a zero-equity guard of its own. |
| `buyAndHoldReturn` | `Performance.totalReturn(firstOpen, lastClose)` |

`winRate`, `tradeCount` and `roundTrips` stay in `Metrics` as-is - they're round-trip trade
accounting, not portfolio-valuation statistics, and have no analytics-library equivalent.

## Portfolio valuation

Package `st.btu.trading.pillar.analytics.portfolio`. Turns an account's executed
`TcsPortfolioOperation`s plus the stored daily closes (`PriceHistoryStorage`) into a daily NAV
series. A FIGI on an operation is mapped to its ticker through
[`InstrumentDirectory`](market-data.md#instrument-directory-instrumentdirectory), and a ticker's
closes are read from the source the ticker is configured to use.

```scala
final case class ValuationPoint(date: LocalDate, value: BigDecimal, invested: BigDecimal, cashFlow: BigDecimal)
final case class ValuationSeries(accountId: AccountId, currency: Currency, points: Vector[ValuationPoint],
                                 unpricedInstruments: Set[String], unmappedFigis: Set[String])
final case class ValuationConfig(baseCurrency: Currency = Currency("RUB"), fxTickers: Map[Currency, Ticker] = Map.empty,
                                 priceLookback: Int = 10)

trait PortfolioValuationService[F[_]] {
  def dailySeries(accountId: AccountId, from: LocalDate, to: LocalDate): F[ValuationSeries]
}
object PortfolioValuationServiceImpl {
  def create[F[_]: Monad](operations: TcsPortfolioOperationStorage[F], directory: InstrumentDirectory[F],
                          instruments: InstrumentStorage[F], sources: MarketDataSources[F],
                          priceHistory: PriceHistoryStorage[F], config: ValuationConfig): PortfolioValuationService[F]
}
```

`ValuationPoint.invested` is the **cumulative** net external flow (deposits positive, withdrawals
negative) from account inception through that date; `cashFlow` is just that date's own flow (`0` on
a day with neither) - this is exactly the `(invested, cashFlow)` pair persisted through
`PortfolioValuationStorage`. One point per calendar day in `[from, to]`, inclusive.

### Rules

1. **Executed only**: an operation counts only when `status.value == "OPERATION_STATE_EXECUTED"`.
2. **Full history loaded**: operations are read from `from` minus 20 years through `to` (not just
   `[from, to]`), so positions, cash and the cumulative `invested` figure are already complete at
   `from` even when the account's real history starts long before the requested window.
3. **Positions per FIGI**, accumulated from `quantity`:
   | Direction | `operationType.value` |
   |---|---|
   | add | `OPERATION_TYPE_BUY`, `_BUY_CARD`, `_BUY_MARGIN`, `_DELIVERY_BUY`, `_INPUT_SECURITIES` |
   | subtract | `OPERATION_TYPE_SELL`, `_SELL_CARD`, `_SELL_MARGIN`, `_DELIVERY_SELL`, `_OUTPUT_SECURITIES`, `_BOND_REPAYMENT_FULL` |
4. **Cash** is the running sum of `amount` over *every* executed operation (commissions, taxes,
   dividends, buys/sells, deposits/withdrawals - all of them), kept as one running total per
   operation `currency`, not just the base currency.
5. **External flows**: `OPERATION_TYPE_INPUT`/`OPERATION_TYPE_OUTPUT` amounts, attributed to their
   own date; `invested` is their running sum, `cashFlow` is that date's own sum, both per currency.
6. **Value**: `cash + Σ quantity × last known close on or before the date`. Bars for every ticker
   with a nonzero position are fetched once per `dailySeries` call, over
   `[from - priceLookback, to]` - `priceLookback` (default 10 days) is how far before `from` a
   carried-forward close may come from. A ticker's bars come **only from the source the ticker is
   configured to use** (`MarketDataSources.settingFor(ticker).source`): bars another source stored
   for the same ticker are never mixed in or used as a fallback, so a ticker with no bars on its
   own source is unpriced rather than priced from the other one.
7. **Currency conversion**: a non-base-currency position or cash balance is converted with the
   latest close on or before the date of that currency's **rate ticker** - the ticker
   `config.fxTickers` names for the currency, otherwise the conventional `"<CCY><baseCurrency>=X"`
   pair (e.g. `"USDRUB=X"`). The rate ticker's bars are read like any other ticker's: from the
   source *that ticker* is configured to use (once per currency per call, over
   `[from - priceLookback, to]`). A rate that a Tinkoff currency instrument carries, such as
   `USD000UTSTOM` on the `CETS` board, is used by naming that ticker for the currency in
   `fxTickers` and setting the ticker to `SourceSetting.Tinkoff(Some(ClassCode("CETS")))`. With
   no FX rate on or before the date, that balance/position is **excluded** from `value` for that
   date and the currency code (cash) or ticker (position) is added to `unpricedInstruments`. A currency
   bucket that nets to exactly `0` is never flagged (nothing was actually excluded). The
   rate-ticker convention and the "latest close on or before a date" lookup itself live in
   `analytics.FxRates`, shared with `tax.TaxServiceImpl` - see [`tax.md`](tax.md#fx-conversion).
8. **Unpriced vs. unmapped**: a FIGI on a buy/sell operation that `InstrumentDirectory.byFigi`
   does not know goes in `unmappedFigis` (checked once per `dailySeries` call, independent of any
   one date) and its position never contributes to `value`. A *mapped* ticker with no close known
   on or before some date is excluded from `value` on that date only and goes in
   `unpricedInstruments` - same set as an FX-conversion failure, since both mean "this ticker/
   currency's contribution to `value` could not be priced".
9. **Price currency and metadata**: a mapped position's price currency is the `currency` of the
   `InstrumentInfo` stored for its ticker (`InstrumentStorage.find`) when that metadata was read
   from the ticker's configured source (`InstrumentInfo.origin.source` equals
   `MarketDataSources.settingFor(ticker).source`), which makes it the currency that source reports
   bars in. Metadata read from another source - what a ticker keeps until its next sync after its
   source is switched - is ignored, like metadata that is not stored: the broker's
   `BrokerInstrument.currency` applies. `name` and `instrumentType` fall back to the broker's record
   the same way; `exchange` has no broker counterpart and is `None` without usable metadata.
10. **Currency codes** are `models.domain.Currency` values, whose only constructor normalises its
    argument: whitespace around the code is trimmed, and a code with no upper-case letter, which is
    how the Tinkoff API spells ISO 4217 codes (`rub`, `usd`), is upper-cased (`RUB`, `USD`). Any
    other code is kept as given, because Yahoo marks a minor unit by case: `GBp` is pence and is not
    folded into `GBP`, pounds, so a position priced in `GBp` finds no rate and is reported unpriced
    rather than valued as if it were in pounds. The Yahoo adapter reports such an instrument in the
    major currency, converting its prices (`GBp`, `ZAc` and `ILA` become `GBP`, `ZAR` and `ILS`; see
    [`market-data.md`](market-data.md#minor-units)), so a minor-unit code reaches the valuation only
    from a source that reports one as such. Operations, instruments, quotes, `baseCurrency` and
    the keys of `fxTickers` all carry that type, so codes compare exactly by construction and no
    service normalises one: `rub` cash and a `RUB` base are one currency, and `USD` metadata with
    `usd` cash is one currency, converted once. The outputs carry `Currency` values:
    `ValuationSeries.currency`, `PositionSnapshot.currency` and `PositionValue.currency`.
    `unpricedInstruments` holds plain strings, since it mixes tickers with the `value` of a
    currency.

### Storage, process and commands

`InstrumentDirectory.byFigi(figi: Figi): F[Option[BrokerInstrument]]` resolves a
`TcsPortfolioOperation.figi` to its ticker (and, as a fallback, its currency, name and type); see
[`market-data.md`](market-data.md#instrument-directory-instrumentdirectory).

```scala
trait PortfolioValuationStorage[F[_]] {
  def upsert(accountId: AccountId, currency: Currency, points: Vector[ValuationPoint]): F[Int]
  def series(accountId: AccountId, from: LocalDate, to: LocalDate): F[Vector[ValuationPoint]]   // chronological
  def latest(accountId: AccountId): F[Option[ValuationPoint]]
}
```

`processes.AnalyticsProcessImpl` mirrors `PriceHistorySyncProcessImpl`: at start, then every
`AnalyticsConfig.interval`, every account from `AccountStorage` gets
`dailySeries(accountId, today - recomputeDays, today)` recomputed and upserted, with per-account
error isolation (one account's failure never stops the others) and a `process-crashed:analytics`
`SystemAlerts` alert if the batch loop itself fails (e.g. `AccountStorage.allAccounts` raises).

`/performance (1m|3m|6m|1y|all, default 1y)` and `/portfolio` read the *stored* series - see
[`telegram-commands.md`](telegram-commands.md#performance) - via `Performance`/`Risk` with
`Valuation(point.date, point.value, point.cashFlow)`. Volatility/Sharpe/max drawdown run on the raw
value series' simple returns (`Returns.simple`), the same convention `strategy.backtest.Metrics`
uses for an equity curve - not TWR-adjusted for intervening cash flows the way `timeWeighted` and
`moneyWeighted` themselves are.

### Positions and allocation

`PortfolioValuationService.positionsAsOf(accountId, date): F[PositionSnapshot]` is a
point-in-time sibling of `dailySeries`, built from the same operation/pricing/FX pipeline
(`loadExecutedOperations`, `resolveInstruments`, `loadTickerBars`/`loadFxBars`, `applyOps`,
`convertBucket`) narrowed to a single `date`: every executed operation up to and including `date`
is folded into one running position/cash state, the same state one step of `dailySeries`'s
day-by-day fold would produce for that date.

```scala
final case class PositionValue(figi: Figi, ticker: Option[Ticker], name: Option[String],
                               instrumentType: Option[String], currency: Option[Currency], exchange: Option[String],
                               quantity: Int, price: Option[BigDecimal], value: Option[BigDecimal], weight: Option[BigDecimal])
final case class PositionSnapshot(date: LocalDate, currency: Currency, positions: Vector[PositionValue],
                                  cash: BigDecimal, total: BigDecimal, unpriced: Int)
```

Rules, sharing a `valueOnePosition` per-figi helper that `valuePositions` (the aggregate `dailySeries`
uses) also delegates to, so both entry points price a position identically:

1. **Positions**: every FIGI with a nonzero net quantity as of `date` (the same accumulation
   `dailySeries` uses) gets one `PositionValue`; a fully closed position (net quantity `0`) is
   omitted entirely, same as `dailySeries` silently skips it.
2. **Unmapped** (the directory does not know the FIGI): `ticker`, `name`, `instrumentType`,
   `currency` and `exchange` are all `None`, and so are `price`/`value`/`weight`.
   `dailySeries` reports this case once per call via `unmappedFigis`; `positionsAsOf` instead folds
   it into the same per-position `unpriced` count as the next case, since both mean "this
   position's contribution to `total` is unknown".
3. **Mapped**: `ticker` comes from the directory; `name`/`instrumentType`/`currency`/`exchange`
   come from the `InstrumentInfo` stored for it from its configured source, with the broker's
   record filling in the first three when there is none (rule 9 above). `price` is the last known
   close on or before `date`, in the position's own `currency` - **unconverted**, unlike `value`.
   `value` is `price * quantity`, converted to `PositionSnapshot.currency` (the same
   `ValuationConfig.baseCurrency` `dailySeries` uses) via the latest close of the currency's rate
   ticker (rule 7 above) - `None` when there is no known close at all, or (for a non-base currency)
   no FX rate on or before `date`; either way this position also counts toward `unpriced`. A
   base-currency instrument's `price` and per-share contribution to `value` coincide; a foreign
   one's don't - `value` is never simply `price * quantity` for those.
4. **Cash**: `cash` is `dailySeries`'s own per-date cash figure - the running sum of every executed
   operation's `amount`, per currency, converted to the base currency (`convertBucket`; an
   inconvertible non-base balance is silently dropped from the sum, same as `dailySeries.value`).
5. **Total and weight**: `total = cash + Σ priced positions' value`. `weight = value / total` for
   every priced position, `None` alongside `value` (also `None`, never divided by zero, on the
   degenerate `total == 0` snapshot). `unpriced` is the count of positions - mapped or not - whose
   `value` is `None`.

`analytics/portfolio/Allocation.scala` is a pure grouping of one snapshot's position weights:

```scala
final case class Allocation(byInstrumentType: Map[String, BigDecimal], byCurrency: Map[String, BigDecimal],
                            byExchange: Map[String, BigDecimal], cashWeight: BigDecimal, unpricedWeightUnknown: Int)
object Allocation { def of(snapshot: PositionSnapshot): Allocation }
```

Every priced position's `weight` is added into three maps, keyed by its own `instrumentType`/
`currency.value`/`exchange`, `"unknown"` for a position missing that field. A priced position is always
mapped, so its type and currency are known; `exchange` is also `None` when no metadata from its
ticker's configured source is stored, and such a position lands in `"unknown"` in the exchange
breakdown only. An unpriced
position (no `weight`) contributes to none of the three - there's nothing to add, its share of
`total` being unknown rather than zero - and is reflected only in `unpricedWeightUnknown`
(`snapshot.unpriced`, carried over as-is). `cashWeight` is `cash / total` (`0` for a zero total, not
a division error) - cash carries no per-currency breakdown of its own (rule 4 above), so it is never
folded into `byCurrency`.

`/positions` and `/allocation` (see [`telegram-commands.md`](telegram-commands.md#positions)) call
`positionsAsOf(accountId, today)` live, unlike `/performance`/`/portfolio` which read the stored
valuation series (`PortfolioValuationStorage`) - nothing persists positions, so this is always
computed fresh from `TcsPortfolioOperationStorage`/`InstrumentDirectory`/`InstrumentStorage`/
`PriceHistoryStorage` on every call.

### Allocation targets, drift and rebalancing

`AllocationTarget` (`analytics/portfolio/AllocationTarget.scala`) is one allocation target,
provided by the consumer through `AllocationTargetStorage`, which the library only reads (see
[`storage-contracts.md`](storage-contracts.md#allocationtargetstoragef_)):

```scala
sealed abstract class Dimension(val value: String) extends StringEnumEntry
object Dimension extends StringEnum[Dimension] {
  case object InstrumentType extends Dimension("instrument_type")
  case object Currency extends Dimension("currency")
  case object Exchange extends Dimension("exchange")
  case object Ticker extends Dimension("ticker")
}

final case class AllocationTarget(id: AllocationTargetId, accountId: AccountId, dimension: Dimension, key: String,
                                  targetPct: BigDecimal, driftThresholdPct: BigDecimal, enabled: Boolean)

trait AllocationTargetStorage[F[_]] { def byAccount(accountId: AccountId): F[List[AllocationTarget]] }   // enabled only, ordered by dimension then key
```

`targetPct`/`driftThresholdPct` are **percentages** (`60` means 60%) - unlike `Allocation`'s own
weight maps, which are 0-1 fractions. `key` is matched exactly against the keys of the matching
`Allocation` breakdown, except that the key of a `Currency` target is first read as a `Currency`
(rule 10 above): targets are entered by hand, and a target entered as `usd` reads the weight of
`USD`. `Drift.key` and `Suggestion.key` keep the key as entered.

`analytics/portfolio/Rebalancer.scala` is pure (no `F[_]`) and reuses `Allocation`/`PositionSnapshot`
directly - it has no storage dependency of its own:

```scala
final case class Drift(dimension: Dimension, key: String, targetPct: BigDecimal, actualPct: BigDecimal,
                       driftPct: BigDecimal, breached: Boolean)
// side: strategy.Side (Buy/Sell) - the same type BacktestEngine/PaperBroker use for a trade's direction.
final case class Suggestion(dimension: Dimension, key: String, side: Side, amount: BigDecimal)

object Rebalancer {
  def drift(allocation: Allocation, snapshot: PositionSnapshot, targets: List[AllocationTarget]): List[Drift]
  def suggest(snapshot: PositionSnapshot, targets: List[AllocationTarget], newCash: Option[BigDecimal]): List[Suggestion]
}
```

Both functions drop disabled targets and preserve `targets`' own order in their result
(`AllocationTargetStorage` already returns only enabled targets, ordered by dimension then key - see
above - but neither function relies on that).

**Actual weight lookup** (`key` matching nothing at all, in any dimension, reads as 0%):

| `dimension` | Actual weight source |
|---|---|
| `InstrumentType` / `Exchange` | The matching `Allocation` breakdown map, by `key` |
| `Currency` | `Allocation.byCurrency`, by `Currency(key).value` (rule 10 above) |
| `Ticker` | `snapshot.positions`' own weights, grouped by `ticker.value` (summed, in the unlikely case two positions share a ticker) - **not** anything in `Allocation`, which has no ticker breakdown |

**`drift`**: `actualPct = actualWeight * 100`; `driftPct = actualPct - targetPct` (percentage
points, signed: positive means overweight, negative underweight); `breached = |driftPct| >
driftThresholdPct` (strictly greater than).

**`suggest`**, always in base currency (`snapshot.currency`):

- **Without `newCash`**: for every target, `actualAmount = actualWeight * snapshot.total` and
  `targetAmount = targetPct% of snapshot.total`; the difference becomes a `Sell` (overweight, down
  to target) or a `Buy` (underweight, up to target), both relative to the *current* total. A target
  already exactly at its amount produces nothing.
- **With `newCash`**: buys only, never sells. Each target's shortfall is `targetPct% of
  (snapshot.total + newCash)` minus `actualWeight * snapshot.total` (the actual amount is *not*
  recomputed against the post-deposit total - the currency currently held in a bucket doesn't
  change just because the pool grew). A target whose shortfall is `<= 0` (already at or above its
  post-deposit target) is excluded entirely rather than turned into a sell. When the deposit covers
  the remaining (underweight) targets' combined shortfall (`newCash >= sum(shortfalls)`), each one
  is bought exactly up to its own post-deposit target amount (`amount = shortfall`) and whatever is
  left of `newCash` is left as cash - no suggestion accounts for it. Otherwise the deposit is spread
  across them in proportion to their shortfall instead: `amount = newCash * shortfall /
  sum(shortfalls)`, so every target narrows its gap by the same fraction but none reaches its
  post-deposit target amount. When every target's shortfall is `<= 0` (or there are no targets),
  `suggest` returns nothing and the whole deposit is left unsuggested.

`/drift` and `/rebalance (cash=<amount>)` (see
[`telegram-commands.md`](telegram-commands.md#drift)) call this pipeline live, the same way
`/positions`/`/allocation` do. `AnalyticsProcessImpl` also calls `Rebalancer.drift` after every
account's daily recompute and dispatches an `AlertKind.AllocationDrift` alert for each breached
target - see [`alerts.md`](alerts.md#allocation-drift-alerts).
