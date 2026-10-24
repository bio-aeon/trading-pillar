# Storage contracts

Package `st.btu.trading.pillar.storages`. Every trait here is a persistence *contract*;
trading-pillar ships no implementation, so a consumer implements the traits it uses over its own
store. Each section below says which methods the library calls to write data, which data it only
reads (the consumer provides that, for example through operator tooling), and the keys it relies
on.

Each section ends with a suggested relational layout. Its table and column names are a starting
point for an implementer, not a schema the library owns or requires: any store that honours the
method semantics works. In those layouts an `account_id` can reference an `accounts` table, a JSON
value can live in a JSON column or in plain JSON text, and decimal values are best held as
fixed-point decimals rather than floating point (the library itself rounds prices to six decimal
places).

A stored currency column holds the `value` of a `models.domain.Currency`, and the library reads it
back with `Currency(text)`, which normalises the code: a code stored in lower case (`rub`) reads
back as `RUB`, and `GBp` stays `GBp`. Persist `Currency.value` and read it through `Currency(...)`.

## `AccountStorage[F[_]]`

```scala
trait AccountStorage[F[_]] { def allAccounts: F[List[Account]] }
```

Read-only from trading-pillar's side: `TcsPortfolioSyncProcess` and `AnalyticsProcess` iterate
`allAccounts`, and the consumer provides the accounts by whatever means it chooses. A relational
implementation could use a table such as `accounts` with an integer `id` (it maps to
`AccountId(value: Int)`), a unique `name` and `created_at`.

## `TcsPortfolioOperationStorage[F[_]]`

```scala
trait TcsPortfolioOperationStorage[F[_]] {
  def lastOperation(accountId: AccountId): F[Option[TcsPortfolioOperation]]
  def operationsByDtRange(accountId: AccountId, since: ZonedDateTime, until: ZonedDateTime): F[Set[TcsPortfolioOperation]]
  def saveOperations(operations: List[TcsPortfolioOperation]): F[Unit]
}
```

Written by the library, via `TcsPortfolioSyncProcess`, through `saveOperations`, which skips any
operation already on record. An operation is identified by `(accountId, extId, operationType)`.
Operations are read back through `lastOperation` and `operationsByDtRange` (by the sync itself, the
valuation and tax services, and `/export`). A relational implementation could use a table such as
`tcs_portfolio_operations` with a generated `id`, one column per remaining `TcsPortfolioOperation`
field, and a unique key on `(account_id, ext_id, operation_type)`.

## `PriceHistoryStorage[F[_]]`

```scala
trait PriceHistoryStorage[F[_]] {
  def lastBarDate(ticker: Ticker, source: Source): F[Option[LocalDate]]
  def bars(ticker: Ticker, source: Source, from: LocalDate, to: LocalDate): F[List[Bar]]
  def latestBars(ticker: Ticker, source: Source, limit: Int): F[List[Bar]]   // chronological
  def upsertBars(bars: List[Bar]): F[Int]
}
```

Written by the library: `PriceHistorySyncService` (driven by `PriceHistorySyncProcess`) calls
`upsertBars`, which must be keyed on `(ticker, date, source)` so the overlap days re-fetched on
every sync replace bars instead of duplicating them; bars from different sources for the same
ticker and date coexist. Read by `BacktestService`, `LiveStrategyService`, `AlertProcess`'s ATR
lookups and the valuation, tax and analysis services. A relational implementation could use a table
such as `price_history` with `symbol`, `date`, `source`, `open`, `high`, `low`, `close`, `adj_close`,
`volume` and a unique key on `(symbol, date, source)`.

## `InstrumentStorage[F[_]]`

```scala
trait InstrumentStorage[F[_]] {
  def find(ticker: Ticker): F[Option[InstrumentInfo]]
  def upsert(info: InstrumentInfo): F[Unit]
}
```

Written by the library: `PriceHistorySyncService` calls `upsert`, refreshing a ticker's metadata
when it is missing, stale, or was read under an origin other than the one being synced - see
[`market-data.md`](market-data.md). Metadata is keyed by ticker, not FIGI, because Yahoo has no
FIGIs; `figi` is an optional Tinkoff cross-reference that nothing looks up by. `origin` says which
setting the row was read under: the source, since two sources can describe one ticker differently
(in another currency, say), and what that source looked the ticker up by, the Tinkoff board or the
Yahoo symbol, since a board or a symbol can describe it differently too. The sync also reads `find`
to compare that origin with the adapter's, and refetches the ticker's whole window when they differ.
The portfolio valuation pipeline reads it through `find` for a position's price currency and uses a
row only when its origin's source is the ticker's configured one (see
[`analytics.md`](analytics.md#portfolio-valuation)); resolving a FIGI to a ticker is the job of
`InstrumentDirectory`, backed by `BrokerInstrumentStorage` below. A relational implementation could
use a table such as `instrument_metadata` with the ticker (`symbol`) as primary key, `source`
(`yahoo` | `tinkoff`, the `Source` values), a nullable `class_code` (the board; null unless the
origin is a Tinkoff board), a nullable `yahoo_symbol` (the Yahoo symbol; null unless the origin is
Yahoo under a symbol), `name`, `instrument_type`, `currency`, `exchange`, `timezone`, a nullable
`figi` and `updated_at`.

`InstrumentInfo.origin` maps to those columns as follows, and back:

| `origin` | `source` | `class_code` | `yahoo_symbol` |
|---|---|---|---|
| `SourceSetting.Yahoo(None)` | `yahoo` | NULL | NULL |
| `SourceSetting.Yahoo(Some(symbol))` | `yahoo` | NULL | `symbol` |
| `SourceSetting.Tinkoff(Some(board))` | `tinkoff` | `board` | NULL |
| `SourceSetting.Tinkoff(None)` | `tinkoff` | NULL | NULL |

The sync compares the whole origin with the adapter's. A Tinkoff adapter always reports a board, so
a `tinkoff` row with no `class_code` never matches: the next sync of its ticker refetches the window
once and rewrites the row. A `yahoo` row matches only when its `yahoo_symbol` is the symbol the
ticker's setting names, so adding, changing or dropping that symbol refetches the window once.

## `WatchlistStorage[F[_]]`

```scala
trait WatchlistStorage[F[_]] {
  def allEnabled: F[List[WatchlistItem]]
  def byAccount(accountId: AccountId): F[List[WatchlistItem]]
  def add(item: WatchlistItem): F[Unit]
  def remove(accountId: AccountId, ticker: Ticker): F[Boolean]   // true if a row was removed
}
```

Written by the library through `add` and `remove`, which back the `/watchlist add` and
`/watchlist rm` Telegram commands. The consumer can also manage items by other means (for example
operator tooling, including switching one off with `enabled`), since the library re-reads the
watchlist on every sync. `allEnabled` drives `PriceHistorySyncProcess`; `byAccount` backs `/watchlist`
and the commands that read the account's tickers. A watchlist item does not say where its ticker's
data comes from - that is a setting of the ticker, see `TickerSourceStorage` below. A relational
implementation could use a table such as `watchlist_items` with `account_id`, `symbol`, `enabled`
(default true), a nullable `note`, `created_at` and a unique key on `(account_id, symbol)`.

## `DividendStorage[F[_]]`

```scala
trait DividendStorage[F[_]] {
  def upsert(events: List[DividendEvent]): F[Int]
  def byTicker(ticker: Ticker, source: Source, from: LocalDate, to: LocalDate): F[List[DividendEvent]]
  def upcoming(tickers: List[SourcedTicker], from: LocalDate): F[List[DividendEvent]]   // exDate >= from
}
```

Written by the library: `PriceHistorySyncServiceImpl.syncSymbol` calls `upsert`, which must be keyed
on `(ticker, exDate, source)` so the overlap window re-fetched on every sync replaces events instead
of duplicating them - see [`market-data.md`](market-data.md#dividends). Events of different sources
for one ticker coexist, so a read names the source: `byTicker` takes it, and `upcoming` takes each
ticker paired with the one source to read it from (`SourcedTicker`). Read for `/dividends` (see
[`telegram-commands.md`](telegram-commands.md)), the analysis service and `/export`, each ticker
from the source it is configured to use. A relational implementation could use a table such as
`dividends` with `symbol`, `ex_date`, `source`, `amount`, a nullable `currency`, `updated_at` and a
unique key on `(symbol, ex_date, source)`; a read filters on `source` as well as `symbol`.

## `TickerSourceStorage[F[_]]`

```scala
trait TickerSourceStorage[F[_]] {
  def find(ticker: Ticker): F[Option[TickerSource]]
  def all: F[List[TickerSource]]
  def upsert(entry: TickerSource): F[Unit]
  def delete(ticker: Ticker): F[Boolean]   // true if a setting was removed
}
```

The per-ticker source settings. Read by the library: `MarketDataSourcesImpl` calls `find` on every
lookup, and a ticker with no setting uses the router's default (see
[`market-data.md`](market-data.md#routing-multiple-sources-marketdatasources)); `/source` lists the
settings through `all`. Written by the library: `/source` and `/watchlist add` call `upsert`, and
`/source SYM default` calls `delete`. The consumer can also manage settings by other means, since
the library re-reads them on every lookup. A relational implementation could use a table such as
`ticker_sources` with the ticker (`symbol`) as primary key, `source` (`yahoo` | `tinkoff`, the
`Source` values), a nullable `class_code` that only a `tinkoff` row may set (a
`SourceSetting.Tinkoff` with no board stores NULL), a nullable `yahoo_symbol` that only a `yahoo` row
may set (a `SourceSetting.Yahoo` with no symbol, which looks the ticker up as given, stores NULL) and
`updated_at`.

## `BrokerInstrumentStorage[F[_]]`

```scala
trait BrokerInstrumentStorage[F[_]] {
  def find(figi: Figi): F[Option[BrokerInstrument]]
  def upsert(instrument: BrokerInstrument): F[Unit]
}
```

Broker reference data keyed by FIGI. Read and written by the library through `TcsInstrumentDirectory`,
which answers a FIGI from `find` when it can and otherwise fetches it from the broker and stores it
with `upsert`, so a FIGI the broker knows is fetched once - see
[`market-data.md`](market-data.md#instrument-directory-instrumentdirectory). A relational
implementation could use a table such as `broker_instruments` with the FIGI (`figi`) as primary key,
`ticker`, `class_code`, `name`, `instrument_type`, `currency` and `updated_at`.

## `BacktestRunStorage[F[_]]`

```scala
trait BacktestRunStorage[F[_]] {
  def save(result: BacktestResult, createdAt: ZonedDateTime): F[BacktestRunId]
  def get(id: BacktestRunId): F[Option[BacktestRun]]
  def recent(limit: Int): F[List[BacktestRun]]
}
```

Written by the library: `BacktestService.run`, `runStrategy` and `runPortfolio` call `save` (so every
`/backtest` is recorded), and `get`/`recent` read runs back. The JSON layout is documented exactly in
[`backtesting.md`](backtesting.md#persistence-and-json-layout). A relational implementation could use
a table such as `backtest_runs` with a generated `id` (returned by `save`), `strategy_id`, `symbol`,
`date_from`, `date_to`, `created_at` and the JSON values `parameters`, `config`, `metrics`,
`trade_log` and `equity_curve`.

## `StrategyBindingStorage[F[_]]`

```scala
trait StrategyBindingStorage[F[_]] {
  def allEnabled: F[List[StrategyBinding]]
  def byAccount(accountId: AccountId): F[List[StrategyBinding]]
}
```

**No write method on this trait by design.** The consumer provides the bindings, for example through
operator tooling, and trading-pillar (`AlertProcess`) only ever reads them, including each binding's
`mode` (`signal` or `paper`; see [`alerts.md`](alerts.md#rule-types) and
[`paper-trading.md`](paper-trading.md#binding-mode)). A relational implementation could use a table
such as `strategy_bindings` with a generated `id`, `account_id`, `strategy_id`, `symbol`, `mode`
(`signal` | `paper`, defaulting to `signal` so a binding only alerts unless paper mode is chosen),
`channels` (a JSON array of channel names), `enabled`, nullable `cooldown_seconds`, `max_per_day` and
`quiet_hours` (JSON, see [`alerts.md`](alerts.md#quiethours)), `created_at` and a unique key on
`(account_id, strategy_id, symbol)`.

## `AlertRuleStorage[F[_]]`

```scala
trait AlertRuleStorage[F[_]] {
  def allEnabledPriceRules: F[List[PriceCrossed]]

  // Row-authoring methods backing /alert (services.commands.AlertRuleHandler): rows created
  // this way are indistinguishable, once persisted, from ones authored by other means.
  def insertPriceRule(rule: NewPriceRule): F[AlertRuleId]
  def delete(accountId: AccountId, id: AlertRuleId): F[Boolean]   // only if owned by accountId
  def byAccount(accountId: AccountId): F[List[PriceCrossed]]      // this account's enabled rules
}
```

Written by the library through `insertPriceRule` and `delete` (with `byAccount` listing an account's
rules), which back the `/alert price|rules|delete` Telegram command - see
[`telegram-commands.md`](telegram-commands.md#alert) - and read by `AlertProcess` through
`allEnabledPriceRules`. As with the watchlist, the consumer can author rules by other means too (for
example operator tooling). `allEnabledPriceRules` returns only enabled price-threshold rules
(`PriceCrossed`).

A relational implementation could use a table such as `alert_rules` with a generated `id`,
`account_id`, `rule_type` (`price_crossed`, the only type the library reads), `symbol`, `parameters`
(JSON, e.g. `{"threshold": 150.5, "direction": "above"}` - this shape belongs to the storage
implementation, not to a trading-pillar codec), `channels` (a JSON array), a nullable `proximity`
(JSON, see [`alerts.md`](alerts.md#proximity)), nullable `cooldown_seconds`, `hysteresis_pct`,
`max_per_day` and `quiet_hours` (JSON), `enabled` and `created_at`. Such an implementation can skip
(and log a warning for) a stored rule whose JSON fails to decode, instead of failing the whole batch.

## `AlertHistoryStorage[F[_]]`

```scala
trait AlertHistoryStorage[F[_]] {
  def lastDelivered(ruleKey: String, ticker: Option[Ticker], kind: AlertKind): F[Option[AlertRecord]]
  def deliveredCountSince(ruleKey: String, since: ZonedDateTime): F[Int]
  def existsDedup(hash: String, since: ZonedDateTime): F[Boolean]
  def record(r: AlertRecord): F[Unit]
  def recent(limit: Int): F[List[AlertRecord]]   // most recent first, delivered and suppressed alike
}
```

Written by the library: `AlertDispatcherImpl.dispatch` calls `record` for every attempt, delivered
or suppressed. Read by every guard that looks at history (`lastDelivered`, `deliveredCountSince` and
`existsDedup`; see [`alerts.md`](alerts.md#guard-chain-alertdispatcher)) and by `/alerts` (`recent`).
A relational implementation could use a table such as `alert_history` with `rule_key`, a nullable
`symbol`, `kind`, `dedup_hash`, `message`, `triggered_at`, a nullable `suppressed_by` and
`channels_sent` (JSON), plus indexes on `(rule_key, symbol, kind, triggered_at)` and
`(dedup_hash, triggered_at)` so both guard lookups are index-backed.

## `PortfolioValuationStorage[F[_]]`

```scala
trait PortfolioValuationStorage[F[_]] {
  def upsert(accountId: AccountId, currency: Currency, points: Vector[ValuationPoint]): F[Int]
  def series(accountId: AccountId, from: LocalDate, to: LocalDate): F[Vector[ValuationPoint]]   // chronological
  def latest(accountId: AccountId): F[Option[ValuationPoint]]
}
```

Written by the library: `AnalyticsProcess` calls `upsert`, keyed on `(account, date)` so recomputing
a day replaces its point. Read by `/performance`, `/portfolio` and `/export` - see
[`telegram-commands.md`](telegram-commands.md#performance) and
[`analytics.md`](analytics.md#portfolio-valuation). A relational implementation could use a table
such as `portfolio_valuations` with `account_id`, `date`, `value`, `invested`, `cash_flow`,
`currency`, `updated_at` and a unique key on `(account_id, date)`.

## `AllocationTargetStorage[F[_]]`

```scala
trait AllocationTargetStorage[F[_]] {
  def byAccount(accountId: AccountId): F[List[AllocationTarget]]   // enabled only, ordered by dimension then key
}
```

No write method, same as `StrategyBindingStorage`: the consumer provides the targets, for example
through operator tooling, and trading-pillar (`Rebalancer`, via
`DriftHandler`/`RebalanceHandler`/`AnalyticsProcessImpl`) only ever reads them. Backs `/drift`,
`/rebalance` and the `AllocationDrift` alert hook - see
[`analytics.md`](analytics.md#allocation-targets-drift-and-rebalancing) and
[`alerts.md`](alerts.md#allocation-drift-alerts). A relational implementation could use a table such
as `allocation_targets` with a generated `id`, `account_id`, `dimension` (`instrument_type` |
`currency` | `exchange` | `ticker`, the values of `Dimension`), `target_key`, `target_pct` (a
percentage: `60` means 60%), `drift_threshold_pct` (percentage points of tolerated drift, for
example defaulting to 5), `enabled` (default true), `created_at` and a unique key on
`(account_id, dimension, target_key)`.

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

Three kinds of data (full contract in [`paper-trading.md`](paper-trading.md#paperstoragef_)):

- **Paper accounts**: the consumer provisions a paper account and its starting cash, for example
  through operator tooling; trading-pillar (`PaperBroker`) only reads it through `account` and
  writes the cash balance through `updateCash`, and never creates an account. A relational
  implementation could use a table such as `paper_accounts` keyed by `account_id`, with `cash`,
  `currency` and `updated_at`.
- **Paper orders**: written by the library, through `insertOrder` from `PaperBroker.execute`, which
  is append-only - every execution, filled or rejected, is recorded. `recentOrders` backs `/paper`'s
  last-5-orders section. A relational implementation could use a table such as `paper_orders` with a
  generated `id`, `account_id`, a nullable `binding_id` (the binding that produced the signal),
  `symbol`, `side` (`buy` | `sell`), `quantity`, `requested_at`, nullable `filled_at`, `fill_price`
  and `commission`, `status` (`pending` | `filled` | `rejected`) and a nullable `reason`.
- **Paper positions**: written by the library, through `upsertPosition` from `PaperBroker.execute`
  (keyed on `(account, ticker)`) and `deletePosition` once a sell brings the quantity to zero. A
  relational implementation could use a table such as `paper_positions` with `account_id`, `symbol`,
  `quantity`, `average_cost`, `updated_at` and a unique key on `(account_id, symbol)`.

## Summary

| Trait | Suggested table | Library writes | Library reads |
|---|---|---|---|
| `AccountStorage` | `accounts` | none | `allAccounts` |
| `TcsPortfolioOperationStorage` | `tcs_portfolio_operations` | `saveOperations` | `lastOperation`, `operationsByDtRange` |
| `PriceHistoryStorage` | `price_history` | `upsertBars` | `lastBarDate`, `bars`, `latestBars` |
| `InstrumentStorage` | `instrument_metadata` | `upsert` | `find` |
| `WatchlistStorage` | `watchlist_items` | `add`, `remove` | `allEnabled`, `byAccount` |
| `TickerSourceStorage` | `ticker_sources` | `upsert`, `delete` | `find`, `all` |
| `BrokerInstrumentStorage` | `broker_instruments` | `upsert` | `find` |
| `DividendStorage` | `dividends` | `upsert` | `byTicker`, `upcoming` |
| `BacktestRunStorage` | `backtest_runs` | `save` | `get`, `recent` |
| `StrategyBindingStorage` | `strategy_bindings` | none | `allEnabled`, `byAccount` |
| `AlertRuleStorage` | `alert_rules` | `insertPriceRule`, `delete` | `allEnabledPriceRules`, `byAccount` |
| `AlertHistoryStorage` | `alert_history` | `record` | `lastDelivered`, `deliveredCountSince`, `existsDedup`, `recent` |
| `PortfolioValuationStorage` | `portfolio_valuations` | `upsert` | `series`, `latest` |
| `AllocationTargetStorage` | `allocation_targets` | none | `byAccount` |
| `PaperStorage` | `paper_accounts` / `paper_orders` / `paper_positions` | `updateCash`, `insertOrder`, `upsertPosition`, `deletePosition` | `account`, `positions`, `recentOrders` |

A trait with no library write ("none" in the third column) is populated entirely by the consumer,
for example through operator tooling, and the library only reads it. `PaperStorage`'s account is
provisioned the same way: `updateCash` only changes the balance of an account that already exists.
