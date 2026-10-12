# Paper trading

Package `st.btu.trading.pillar.paper` (models, `PaperBroker`/`PaperBrokerImpl`), plus
`storages.PaperStorage`, `alerts.BindingMode` and the `/paper` command
(`services.commands.PaperHandler`). Paper trading lets a strategy prove itself on live prices
without real orders; the library does not place real-money orders.

## Binding mode

```scala
sealed abstract class BindingMode(val value: String) extends StringEnumEntry
object BindingMode extends StringEnum[BindingMode] {
  case object Signal extends BindingMode("signal")   // default
  case object Paper extends BindingMode("paper")
}
```

`alerts.StrategyBinding` carries a `mode: BindingMode` field. A `Signal` binding only delivers
alerts through `AlertDispatcher`. A `Paper` binding *additionally* has every `Buy`/`Sell` signal
from its own live evaluation executed through `PaperBroker`, independent of (and not subject to)
the alert delivery guards - see [`alerts.md`](alerts.md#paper-trading) for exactly where this is
wired into `AlertProcessImpl`, and
[`alerts.md`](alerts.md#live-evaluation-and-the-shadow-position-rule) for `LiveEvaluation`, the
carrier of those signals.

## Models

```scala
final case class PaperAccount(accountId: AccountId, cash: BigDecimal, currency: String)

@newtype final case class PaperOrderId(value: Long)

sealed abstract class PaperOrderStatus(val value: String) extends StringEnumEntry
object PaperOrderStatus extends StringEnum[PaperOrderStatus] {
  case object Pending extends PaperOrderStatus("pending")   // not currently produced - every
  case object Filled extends PaperOrderStatus("filled")     // PaperBroker.execute call fills or
  case object Rejected extends PaperOrderStatus("rejected") // rejects synchronously, never leaves
}                                                            // an order pending.

final case class PaperOrder(
  id: Option[PaperOrderId],            // None until PaperStorage.insertOrder assigns one
  accountId: AccountId,
  bindingId: Option[StrategyBindingId],// the binding that produced the signal; always Some in practice
  ticker: Ticker,
  side: Side,                          // Buy | Sell (strategy.Side, same type Trade uses)
  quantity: Long,                      // whole shares; the resolved/filled amount, 0 if rejected
  requestedAt: ZonedDateTime,
  filledAt: Option[ZonedDateTime],     // Some iff status == Filled
  fillPrice: Option[BigDecimal],       // Some iff status == Filled
  commission: Option[BigDecimal],      // Some iff status == Filled
  status: PaperOrderStatus,
  reason: Option[String]               // the signal's own reason when filled; the reject reason
)                                      // (which itself embeds the signal's reason) when rejected

final case class PaperPosition(
  accountId: AccountId, ticker: Ticker, quantity: Long, averageCost: BigDecimal, updatedAt: ZonedDateTime
)
```

`PaperOrderStatus.Pending` is never produced by the library: `PaperBroker.execute` always resolves
synchronously to `Filled` or `Rejected` in the same call. The case exists so an order that another
tool recorded as pending (for example one inserted by hand) still decodes.

## `PaperStorage[F[_]]`

```scala
trait PaperStorage[F[_]] {
  def account(accountId: AccountId): F[Option[PaperAccount]]
  def updateCash(accountId: AccountId, cash: BigDecimal): F[Unit]
  def insertOrder(order: PaperOrder): F[PaperOrderId]
  def positions(accountId: AccountId): F[List[PaperPosition]]
  def upsertPosition(position: PaperPosition): F[Unit]
  def deletePosition(accountId: AccountId, ticker: Ticker): F[Unit]
  def recentOrders(accountId: AccountId, limit: Int): F[List[PaperOrder]]   // most recent first
}
```

Backs three kinds of data (a suggested relational layout is in
[`storage-contracts.md`](storage-contracts.md#paperstoragef_)):

| Data | Written by the library | Read by the library |
|---|---|---|
| Paper account (cash, currency) | `updateCash` only; the consumer provisions the account and sets or tops up its starting cash | `account` |
| Paper orders | `insertOrder`, append-only | `recentOrders` (`/paper`) |
| Paper positions | `upsertPosition`, `deletePosition` | `positions` |

Unlike the watchlist and the alert rules, which the library can create as well as the consumer,
trading-pillar never *creates* a paper account - the consumer provisions one (and its starting cash),
for example through operator tooling, before a binding can trade in paper mode; `PaperBroker` only
ever reads it and updates its `cash`.

## `PaperBroker[F[_]]`

```scala
trait PaperBroker[F[_]] {
  def execute(binding: StrategyBinding, signal: StrategySignal, quote: Quote, now: ZonedDateTime): F[PaperOrder]
}

final case class PaperBrokerConfig(commissionRate: BigDecimal, slippageBps: Int)
```

**A live paper fill uses the latest available quote, not a next-bar open.** `BacktestEngine` fills
a signal produced on bar *t* at bar *t+1*'s open, specifically to remove look-ahead bias from a
simulation that already knows every future bar. Live trading has no such future bar to look ahead
into in the first place - "the next available price" *is* the latest quote - so `PaperBroker.execute`
fills immediately against the `Quote` its caller (`AlertProcessImpl`) just fetched, with the exact
same slippage/commission model as the backtest engine applied to that quote's `price` instead of a
bar's `open`.

### Sizing and fill-price math (shared with `BacktestEngine`)

`strategy.SizingResolution` is the pure helper both `BacktestEngine` and `PaperBrokerImpl` call -
one implementation, so a live paper fill and a backtest fill resolve the exact same `Sizing` the
exact same way (see [`backtesting.md`](backtesting.md#execution-fill-at-next-open) for the full
breakdown of each `Sizing` variant):

```scala
buyFillPrice  = quote.price * (1 + slippageBps / 10000)
sellFillPrice = quote.price * (1 - slippageBps / 10000)
commission    = commissionRate * (quantity * fillPrice)
```

Long only, whole shares: a buy quantity is always `min(sizing-resolved quantity, maxAffordableQty)`
and a sell quantity is always `min(sizing-resolved quantity, position.quantity)` - `PaperBroker`
never opens a short position or oversells one.

**`equity` is single-ticker.** `Sizing.FractionOfEquity` resolves against `equity = account.cash +
this signal's own ticker's position quantity * quote.price` - cash plus this ticker's position
only, not the sum of every position the paper account holds. A binding sized as a fraction of
"equity" is therefore a fraction of cash plus that one ticker's own stake, the same simplification
on both the buy and the sell side.

### Reject reasons

`execute` never raises for a business-rule rejection - it always returns a `PaperOrder` with
`status = Rejected`, `quantity = 0` and `reason` set, in this order of precedence:

| Reason (as worded in `reason`) | Condition | Side |
|---|---|---|
| `No paper account for account <id>` | `PaperStorage.account` returns `None` | Buy and Sell |
| `Insufficient cash to buy <TICKER> (<signal reason>)` | cash can't cover even one share (`maxAffordableQty <= 0`) - checked *before* the sizing's own resolved quantity, so this is reported instead of "zero quantity" whenever cash, not the sizing, is the binding constraint | Buy only |
| `Sell without a position (<signal reason>)` | no `PaperPosition` exists for the ticker - checked, on the sell side, at the same step as "insufficient cash" on the buy side, before the sizing's own resolved quantity | Sell only |
| `Buy resolved to zero quantity (<signal reason>)` / `Sell resolved to zero quantity (<signal reason>)` | the resolved quantity is `<= 0` for any other reason (e.g. an explicit `Sizing.Quantity(0)`, or a `FractionOfEquity` too small to round up to one share) | Buy and Sell |

A `StrategySignal.Hold` is never passed to `execute` by `AlertProcessImpl` (`LiveEvaluation.signals`
only ever contains `Buy`/`Sell`); `PaperBrokerImpl` raises `IllegalArgumentException` rather than
accept one, since there is no `Side` to record a `PaperOrder` with.

### On a fill

- **Cash**: `PaperStorage.updateCash` to `account.cash - notional - commission` on a buy, or
  `account.cash + notional - commission` on a sell - commission is always subtracted; only the
  notional's sign flips, matching `BacktestEngine`'s cash accounting exactly.
- **Position, buy**: `PaperStorage.upsertPosition` - a new `PaperPosition` at the fill price, or
  (for an existing one) a quantity-weighted average cost, identical to `BacktestEngine.applyBuy`.
- **Position, sell**: the filled quantity is subtracted; `PaperStorage.deletePosition` once it
  reaches zero, otherwise `upsertPosition` with the reduced quantity and the *same* `averageCost`
  (a partial sell never changes cost basis, matching `BacktestEngine.applySell`).
- **Order**: `PaperStorage.insertOrder` with `status = Filled`, `filledAt = now`, the resolved
  `fillPrice`/`commission`, and `reason` set to the signal's own reason (so a filled order's reason
  reads like `Trade.reason` in a backtest, not a rejection explanation).

## `/paper` command

See [`telegram-commands.md`](telegram-commands.md#paper) for the exact reply format: cash, each
position (quantity, average cost, latest price, value, P&L - or marked unpriced if no quote is
available right now), total equity, and the last 5 orders.
