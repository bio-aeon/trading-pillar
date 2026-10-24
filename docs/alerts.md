# Alerts

Package `st.btu.trading.pillar.alerts`. Two independent things get evaluated on every poll of
`AlertProcess` - price-threshold rules (`PriceCrossed`) and live strategy signals
(`StrategyBinding`) - and both flow through the same `AlertDispatcher` guard chain before delivery.

## Alert kinds

```scala
sealed abstract class AlertKind(val value: String) extends StringEnumEntry
object AlertKind extends StringEnum[AlertKind] {
  case object PreTarget extends AlertKind("pre_target")
  case object TargetHit extends AlertKind("target_hit")
  case object StrategyBuy extends AlertKind("strategy_buy")
  case object StrategySell extends AlertKind("strategy_sell")
  case object StrategyPreBuy extends AlertKind("strategy_pre_buy")
  case object StrategyPreSell extends AlertKind("strategy_pre_sell")
  case object System extends AlertKind("system")
  case object AllocationDrift extends AlertKind("allocation_drift")
}
```

`PreTarget`/`TargetHit` come from `PriceCrossed` rules; `StrategyBuy`/`StrategySell`/
`StrategyPreBuy`/`StrategyPreSell` come from `StrategyBinding` live evaluation. `System` is for
infrastructure alerts (sync failures, a crashed process loop, ...) and is the one kind that bypasses
the quiet-hours guard - see [System alerts](#system-alerts) below for who raises it and how.
`AllocationDrift` comes from `AllocationTarget`s - see
[Allocation drift alerts](#allocation-drift-alerts) below.

```scala
final case class Alert(
  ruleKey: String,
  symbol: Option[Ticker],
  kind: AlertKind,
  title: String,
  body: String,
  triggerValue: Option[BigDecimal],
  at: ZonedDateTime
) {
  def dedupHash: String  // SHA-256 hex of symbol|kind|title|body|triggerValue - deliberately excludes ruleKey
}
```

`dedupHash` excludes `ruleKey` on purpose: it is a second, independent dedup layer (on top of the
`(ruleKey, symbol, kind)` cooldown key) that also catches two *different* rules configured to emit
the same notification.

## Rule types

```scala
sealed trait AlertRule {
  def ruleKey: String; def accountId: AccountId; def ticker: Ticker
  def channels: List[String]; def policy: DeliveryPolicy
}

final case class PriceCrossed(
  id: AlertRuleId, accountId: AccountId, ticker: Ticker,
  threshold: BigDecimal, direction: PriceCrossed.Direction,   // Above | Below
  proximity: Option[Proximity], channels: List[String], policy: DeliveryPolicy
) extends AlertRule   // ruleKey = s"price:${id.value}"

final case class StrategyBinding(
  id: StrategyBindingId, accountId: AccountId, strategyId: StrategyId, ticker: Ticker,
  mode: BindingMode,   // Signal | Paper - see "Paper trading" below
  channels: List[String], policy: DeliveryPolicy
) extends AlertRule   // ruleKey = s"binding:${id.value}"

sealed abstract class BindingMode(val value: String) extends StringEnumEntry
object BindingMode extends StringEnum[BindingMode] {
  case object Signal extends BindingMode("signal")   // default - alerts only
  case object Paper extends BindingMode("paper")     // alerts, plus PaperBroker execution
}
```

`StrategyBindingStorage.allEnabled`/`byAccount` are read-only - the consumer provides the bindings
(see [`storage-contracts.md`](storage-contracts.md)), including each binding's `mode`.
`AlertRuleStorage` has three more methods alongside `allEnabledPriceRules`:

```scala
trait AlertRuleStorage[F[_]] {
  def allEnabledPriceRules: F[List[PriceCrossed]]
  def insertPriceRule(rule: NewPriceRule): F[AlertRuleId]
  def delete(accountId: AccountId, id: AlertRuleId): F[Boolean]   // only the account's own rule
  def byAccount(accountId: AccountId): F[List[PriceCrossed]]      // the account's enabled price rules
}

final case class NewPriceRule(
  accountId: AccountId, ticker: Ticker, threshold: BigDecimal, direction: PriceCrossed.Direction,
  proximity: Option[Proximity], channels: List[String],
  cooldown: Option[FiniteDuration],   // None -> DeliveryPolicy.DefaultCooldown
  maxPerDay: Option[Int]              // None -> unlimited
)
```

So a price rule (`PriceCrossed`) can be authored two ways: directly from Telegram via
`/alert price ...`/`/alert delete` (see [`telegram-commands.md`](telegram-commands.md#alert)) or by
the consumer's own tooling writing to the same store - rules created either way are
indistinguishable once persisted, and both flow through the same `AlertProcess`/`AlertDispatcher`
pipeline described below.
`insertPriceRule` never sets `hysteresisPct` or `quietHours` (no command syntax for them yet), so a
Telegram-created rule always gets `DeliveryPolicy`'s default `hysteresisPct` and no quiet hours.

## `DeliveryPolicy` defaults

```scala
final case class DeliveryPolicy(
  cooldown: FiniteDuration = 1.hour,
  hysteresisPct: BigDecimal = BigDecimal("0.5"),
  maxPerDay: Option[Int] = None,
  quietHours: Option[QuietHours] = None
)
```

`hysteresisPct` is a percentage (consistent with `Proximity.Percent`, also a percentage - `0.5`
means 0.5%, not 0.005).

## `Proximity`

```scala
sealed trait Proximity
object Proximity {
  final case class Percent(p: BigDecimal) extends Proximity   // % of the threshold
  final case class Absolute(x: BigDecimal) extends Proximity  // absolute price delta
  final case class Atr(multiplier: BigDecimal, period: Int) extends Proximity  // multiplier * ATR(period)
}
```

Band width per variant, as used by `AlertEngine.evaluatePriceRule`:

| Variant | Band |
|---|---|
| `Percent(p)` | `abs(threshold) * p / 100` |
| `Absolute(x)` | `x` |
| `Atr(multiplier, period)` | `multiplier * ATR(period)` - `None` (no pre-target alert) if the caller didn't supply an ATR value; `AlertProcessImpl` computes it from `PriceHistoryStorage.latestBars(ticker, source, period + 1)` |

### JSON shape (circe, exactly as the code encodes it)

`Proximity`'s codec comes from circe's generic coproduct derivation (`deriveCodec`) with **no**
discriminator field config, so each case wraps its fields in an object keyed by the exact
case-class name:

```json
{"Percent": {"p": 1}}
{"Absolute": {"x": 2.5}}
{"Atr": {"multiplier": 1.5, "period": 14}}
```

This is the literal shape a stored proximity must have: a storage implementation, and any tool that
authors rules directly, must read and write exactly this JSON (for example in a nullable JSON
column). Leave the value out (or store JSON `null`) to disable pre-target alerts for a rule.

## `QuietHours`

```scala
final case class QuietHours(start: LocalTime, end: LocalTime, zone: ZoneId)
```

A daily window, in `zone` local time, during which non-`System` alerts are suppressed. Overnight
windows (`start` after `end`, e.g. 22:00-07:00) wrap past midnight
(`AlertDispatcherImpl.withinQuietHours`: `local >= start || local < end` when `start > end`, else
`local >= start && local < end`).

### JSON shape (circe)

`start`/`end` use circe's built-in `Encoder[LocalTime]`/`Decoder[LocalTime]` (ISO local time,
seconds included when non-zero); `zone` uses circe's built-in `Encoder[ZoneId]`/`Decoder[ZoneId]`
(the zone id string):

```json
{"start": "22:00:00", "end": "07:00:00", "zone": "Europe/Moscow"}
```

## `AlertEngine.evaluatePriceRule` (price-threshold rules)

```scala
object AlertEngine {
  def evaluatePriceRule(
    rule: PriceCrossed,
    previous: Option[Quote],   // the caller (AlertProcess) remembers this per ticker across polls
    current: Quote,
    atr: Option[BigDecimal]
  ): List[Alert]
}
```

Pure function; detecting an actual cross needs the *previous* quote, which `AlertProcessImpl` keeps
in a `Ref[F, Map[Ticker, Quote]]` and updates after every evaluation.

1. **Cross check first**: if `previous` exists and the price crossed the threshold in `direction`
   between `previous` and `current` (`Above`: `previous < threshold <= current`; `Below`:
   `previous > threshold >= current`), emit exactly one `TargetHit` alert - proximity is not
   considered at all in this branch.
2. **Otherwise, proximity check**: only if the rule has a `proximity`, the current price is still
   on the "wrong" side of the threshold (hasn't crossed yet), and `abs(current.price - threshold)`
   is within the configured band, emit one `PreTarget` alert.
3. Otherwise, no alert (empty list) - this is the common case on every poll where nothing
   interesting is happening.

Both alert bodies are plain text, e.g. `"AAPL rose above 300 (now 301.20)"` (`TargetHit`) and
`"AAPL at 298.10, 1.90 away from 300"` (`PreTarget`).

## Guard chain (`AlertDispatcher`)

```scala
trait AlertDispatcher[F[_]] {
  def dispatch(alert: Alert, policy: DeliveryPolicy, channels: List[String]): F[DispatchOutcome]
  def mute(until: Instant): F[Unit]   // suppresses every alert, regardless of rule, until this instant
  def unmute: F[Unit]
}
sealed trait DispatchOutcome
object DispatchOutcome {
  final case class Delivered(channels: List[String]) extends DispatchOutcome
  final case class Suppressed(reason: String) extends DispatchOutcome
}
```

`AlertDispatcherImpl.dispatch` runs six checks **in this exact order**, short-circuiting on the
first suppression; every dispatch (delivered or suppressed) is recorded via
`AlertHistoryStorage.record`:

| # | Guard | `suppressedBy` value | Data source | Applies to |
|---|---|---|---|---|
| 1 | Mute | `"mute"` | in-memory `Ref[F, Option[Instant]]` set by `mute`/`unmute` | every alert |
| 2 | Cooldown | `"cooldown"` | `AlertHistoryStorage.lastDelivered(ruleKey, symbol, kind)` - suppressed if `alert.at` is before `lastDelivered.deliveredAt + policy.cooldown` | every alert, keyed by `(ruleKey, symbol, kind)` |
| 3 | Hysteresis | `"hysteresis"` | in-memory `Ref[F, Map[String, BigDecimal]]` (last delivered `triggerValue` per `ruleKey`) | **only** `PreTarget`/`TargetHit` (threshold-kind) alerts - suppressed if the new `triggerValue` is within `policy.hysteresisPct`% of the last delivered value |
| 4 | Dedup | `"dedup"` | `AlertHistoryStorage.existsDedup(alert.dedupHash, alert.at - policy.cooldown)` | every alert |
| 5 | Daily cap | `"daily_cap"` | `AlertHistoryStorage.deliveredCountSince(ruleKey, startOfLocalDay)` `>= policy.maxPerDay` | every alert, only if `maxPerDay` is set |
| 6 | Quiet hours | `"quiet_hours"` | `policy.quietHours`, evaluated against `alert.at` in the configured zone | every alert **except** `AlertKind.System`, which always bypasses this one guard (every other guard above still applies to it) |

Strategy alerts (`StrategyBuy`/`StrategySell`/`StrategyPreBuy`/`StrategyPreSell`) skip guard 3
entirely (`isThresholdKind` is `false` for them) - they rely instead on `LiveStrategyService`'s own
state machine (see [Live evaluation](#live-evaluation-and-the-shadow-position-rule) below) to avoid
re-firing every poll.

On delivery, `channels.traverse` sends to every named `AlertChannel`; an unknown channel name or a
channel that raises is logged and counted as *not sent* rather than failing the whole dispatch. If
`channels` was non-empty but **none** of them actually sent (every name unknown, or every `send`
raised), the attempt is recorded the same way the six guards above are - `suppressedBy =
Some("delivery_failed")`, `channels = Nil` - and `dispatch` returns
`DispatchOutcome.Suppressed("delivery_failed")` instead of `Delivered`; the hysteresis `Ref` is
*not* updated in this case, so the next attempt is not held back by it. Otherwise, only
threshold-kind alerts with a `triggerValue` update the hysteresis `Ref`. The persisted
`AlertRecord` always has `message = s"${alert.title}: ${alert.body}"`, and `channels = Nil` when
suppressed.

## Channels

```scala
trait AlertChannel[F[_]] { def name: String; def send(alert: Alert): F[Unit] }
```

`TelegramAlertChannel` (`name = "telegram"`) sends via `TgGateway.sendMessage` with legacy
`parse_mode=Markdown`: `*<title>*<space+ticker if present>\n<body>`, with both title and body run
through the same `models.tg.MarkdownSanitizer` the command-reply path uses (`CommandReply
.prepare`): `*`, `` ` ``, `[` and `]` removed, and `_` turned into a space rather than dropped
(legacy Markdown only needs escaping the characters that open a formatting span, and trading-pillar's
own identifiers use `_`, e.g. `strategy_buy`). An `AlertKind.System` alert gets a `SYSTEM: ` prefix ahead of the bold title
(`SYSTEM: *<title>*\n<body>`), so it stands out from ordinary per-symbol/per-strategy alerts in the
chat.

## Live evaluation and the shadow-position rule

```scala
trait LiveStrategyService[F[_]] {
  def evaluate(binding: StrategyBinding, strategy: Strategy, now: ZonedDateTime): F[LiveEvaluation]
}

final case class LiveEvaluation(alerts: List[Alert], signals: List[StrategySignal])
```

`LiveStrategyServiceImpl.evaluate`, per binding, per poll:

1. Load `PriceHistoryStorage.latestBars(binding.ticker, marketDataSource.origin.source,
   strategy.warmupBars)` into a `BarHistory`.
2. **onBar path**: if `history.size >= strategy.warmupBars` and there is a latest bar, look up this
   binding's in-memory state (`lastBarDate`, shadow `position`). If `lastBarDate` is already `>=`
   the latest bar's date, **skip** - `onBar` for a given bar date is only ever invoked once per
   binding, however many times the process polls before the next bar closes. Otherwise call
   `strategy.onBar`, update the shadow position (see below) and the state's `lastBarDate`, turn any
   `Buy`/`Sell` signal into a `StrategyBuy`/`StrategySell` `Alert` (`Hold` produces none), and keep
   the same bar's `Buy`/`Sell` signals (only - never `Hold`) on `LiveEvaluation.signals`.
3. **Proximity path**: independently (no warmup gate, no once-per-bar-date gate), fetch
   `marketDataSource.latestQuote(binding.ticker)`. If present, build a provisional flat `Bar`
   (`open = high = low = close = quote.price`, `volume = 0`, today's date) and call
   `strategy.proximity(ctx, provisionalBar)` using the *current* shadow position. A `PreSignal`
   becomes a `StrategyPreBuy`/`StrategyPreSell` `Alert` depending on its `side`. Proximity never
   contributes to `LiveEvaluation.signals` - only a closed bar's own onBar signals do.
4. `LiveEvaluation.alerts` is the concatenation of both paths' alerts (0, 1 or 2 alerts per binding
   per poll); `LiveEvaluation.signals` carries the onBar path's own signals regardless of whether
   the matching alert is actually delivered or suppressed by a guard below - see
   [Paper trading](#paper-trading) for who consumes it and why.

### Shadow position

trading-pillar has no visibility into any real brokerage position (that lives entirely in the
consumer app). `LiveStrategyServiceImpl` instead derives a **shadow position purely from this
binding's own live signal history**: flat until a `Buy` alert fires for this binding, then treated
as a 1-share position (at that bar's close) until the next `Sell` alert for the same binding
flattens it again. This shadow position is what `StrategyContext.position` is set to for *both* the
`onBar` and `proximity` calls (with `portfolio = PortfolioView(0, 0)` always, since there is no
cash/equity concept live). It is independent per `binding.ruleKey`, kept in an in-memory
`Ref[F, Map[String, LiveState]]` - it is **not** persisted, so it resets to flat on process restart
regardless of what the strategy's backtest or a real brokerage position would show.

## `AlertProcess`

```scala
trait AlertProcess[F[_]] { def run: Stream[F, Unit] }
final case class AlertProcessConfig(pollInterval: FiniteDuration, rateLimitBackoff: FiniteDuration)
```

Every `pollInterval` (for example `5m`), `AlertProcessImpl`:

- Loads `StrategyBindingStorage.allEnabled` and `AlertRuleStorage.allEnabledPriceRules`.
- Evaluates and dispatches each binding and each rule **independently, with per-item error
  isolation** - one binding whose strategy id isn't registered (logged as a warning) or one rule
  whose quote lookup fails never stops the others in the same batch.
- A `MarketDataError.RateLimited(retryAfter)` while evaluating a price rule logs a warning and
  sleeps `retryAfter` (falling back to `config.rateLimitBackoff`) before the loop continues.
- Mirrors `PriceHistorySyncProcessImpl`: `ProcessContext`/`tofu.WithLocal`, tofu logging, and
  self-restart on an uncaught loop failure - which also raises `process-crashed:alerts` through the
  `SystemAlerts[F]` it now takes as a constructor/`create` parameter (see below).
- After dispatching a binding's alerts, also executes its `LiveEvaluation.signals` through the
  `PaperBroker[F]` it takes as a constructor/`create` parameter, for bindings with `mode == Paper`
  only - see [Paper trading](#paper-trading).

## Paper trading

See [`paper-trading.md`](paper-trading.md) for the full contract (`PaperBroker`, `PaperStorage`,
the four reject reasons, the `/paper` command). In short: `AlertProcessImpl.evaluateAndDispatchBinding`
dispatches `LiveEvaluation.alerts` exactly as before, then - independent of the dispatch outcome and
only for a `StrategyBinding` with `mode == BindingMode.Paper` - fetches one quote for the binding's
ticker (only when there is at least one signal to execute, so a `Signal`-mode binding never pays for
it) and calls `PaperBroker.execute(binding, signal, quote, now)` once per signal in
`LiveEvaluation.signals`, logging the resulting `PaperOrder`. Paper executions are **not** subject to
the guard chain below (mute, cooldown, hysteresis, dedup, daily cap, quiet hours) - those guard the
*alert*, not the trade.

## System alerts

Infrastructure-level alerts - process crashes, a market-data source failing repeatedly - as opposed
to the per-symbol/per-strategy alerts above. `SystemAlerts`/`SystemAlertsImpl` and `FailureTracker`/
`FailureTrackerImpl` both live in `alerts/SystemAlerts.scala` and `alerts/FailureTracker.scala`
respectively (trait, impl and `create`/`noop` factory together in one file each):

```scala
trait SystemAlerts[F[_]] { def raise(name: String, title: String, body: String): F[Unit] }
object SystemAlerts { def noop[F[_]: Applicative]: SystemAlerts[F] }

object SystemAlertsImpl {
  def create[I[_]: Functor, F[_]: Monad: Clock](
    dispatcher: AlertDispatcher[F],
    channels: List[String],
    policy: DeliveryPolicy = DeliveryPolicy()   // cooldown 1h, no quiet hours (irrelevant: bypassed anyway)
  )(implicit logs: Logs[I, F]): I[SystemAlerts[F]]
}

trait FailureTracker[F[_]] {
  def failed(key: String): F[Int]      // increments and returns the new consecutive-failure count
  def succeeded(key: String): F[Unit]  // resets the key's count to zero
}
object FailureTrackerImpl {
  def create[I[_]: Sync, F[_]: Sync](implicit logs: Logs[I, F]): I[FailureTracker[F]]
}
```

`SystemAlertsImpl.raise(name, title, body)` builds `Alert(s"system:$name", None, AlertKind.System,
title, body, None, now)` and runs it through the very same `AlertDispatcher.dispatch` every other
alert goes through, with the given `channels` and `policy` - so it gets the full guard chain (mute,
cooldown, dedup, daily cap) **except** quiet hours, which `AlertDispatcherImpl` bypasses for
`AlertKind.System` (see the guard chain table above). `SystemAlerts.noop[F]` is a no-op
implementation (for callers, like `TcsPortfolioSyncProcessImpl`, where a consumer hasn't wired one
up); it never dispatches anything.

`FailureTrackerImpl` is a plain `Ref[F, Map[String, Int]]`-backed consecutive-failure counter,
in-memory only (resets on process restart, not shared across process instances) - it has no opinion
about *when* to raise anything; that policy lives in the caller.

### Hooks that raise System alerts

| Raised by | `name` | When |
|---|---|---|
| `PriceHistorySyncProcessImpl` | `sync-failed:<ticker>` | The 3rd consecutive `syncSymbol` failure for that ticker (`FailureTracker.failed` reaches exactly 3) - raised once, not on every failure past the 3rd |
| `PriceHistorySyncProcessImpl` | `sync-recovered:<ticker>` | The next `syncSymbol` success for a ticker that previously crossed the failure threshold (tracked in-process, independently of `FailureTracker`'s own per-key count, so it pairs 1:1 with a `sync-failed` that was actually raised) |
| `PriceHistorySyncProcessImpl` | `process-crashed:price-history-sync` | The loop-level `handleErrorWith` catches an otherwise-uncaught exception from a sync batch |
| `AlertProcessImpl` | `process-crashed:alerts` | The loop-level `handleErrorWith` catches an otherwise-uncaught exception from an evaluation batch |
| `TcsPortfolioSyncProcessImpl` | `process-crashed:tcs-portfolio-sync` | The per-account loop-level `handleErrorWith` catches an otherwise-uncaught exception |

`PriceHistorySyncProcessImpl.create` and `AlertProcessImpl.create` both take `SystemAlerts[F]` (and
`PriceHistorySyncProcessImpl` also a `FailureTracker[F]`) as required constructor/`create`
parameters. `TcsPortfolioSyncProcessImpl.create`/`.resource` instead have a `SystemAlerts[F]`
overload that defaults it to `SystemAlerts.noop[F]` (a literal default value isn't possible there:
Scala can't see a method's own context-bound implicits, like the `Async[F]` `SystemAlerts.noop[F]`
needs, while elaborating a default for an earlier parameter in the same signature) - so existing
two-argument-fewer call sites keep compiling unchanged.

## Allocation drift alerts

Unlike every other alert kind above, `AlertKind.AllocationDrift` is not dispatched by `AlertProcess`
on its poll loop - it comes from `processes.AnalyticsProcessImpl`, right after that
process's own daily `PortfolioValuationStorage.upsert` for an account:

1. Load the account's `AllocationTargetStorage.byAccount` targets.
2. Compute today's `PositionSnapshot`/`Allocation` (the same pipeline `/positions`/`/allocation`
   use) and run `Rebalancer.drift` - see
   [`analytics.md`](analytics.md#allocation-targets-drift-and-rebalancing) for the drift/
   breach rules.
3. For every **breached** target, dispatch one `Alert` through the very same `AlertDispatcher` -
   and therefore the same six-guard chain above - with:
   - `ruleKey = s"drift:${accountId}:${dimension.value}:${key}"` (so cooldown/dedup/daily-cap are
     scoped per account, per dimension, per key - one target drifting doesn't cool down another),
   - `symbol = None` (a target's `key` isn't always a ticker),
   - `title`/`body` naming the dimension/key and the target/actual/drift percentages,
   - `triggerValue = Some(driftPct)` (not used for hysteresis: `isThresholdKind` only matches
     `PreTarget`/`TargetHit`, so an `AllocationDrift` alert never re-arms on movement - it simply
     re-fires once the cooldown below elapses, as long as the target is still breached),
   - `DeliveryPolicy(cooldown = 1.day)` (the other fields - `hysteresisPct`, `maxPerDay`,
     `quietHours` - stay at their defaults; `hysteresisPct` is moot per the previous point).
4. `AnalyticsProcessImpl.create`'s `channels: List[String]` parameter is every `AllocationDrift`
   alert's delivery target - the same configured channel-name list a consumer would pass to any
   other `DeliveryPolicy`-driven alert.

`/drift` (see [`telegram-commands.md`](telegram-commands.md#drift)) runs the same
`Rebalancer.drift` computation on demand and prints every enabled target, breached or not - it does
not itself dispatch anything through `AlertDispatcher`.

## Commands that touch alerts

`/mute (minutes)`, `/unmute`, `/alerts (n)`, `/alert price ...`/`/alert rules`/`/alert delete <id>`
(rule management), `/drift` (read-only preview of the same drift computation the process alerts
from), `/paper` (the paper-trading account fed by paper-mode bindings, see
[`paper-trading.md`](paper-trading.md)) - see [`telegram-commands.md`](telegram-commands.md).
