# AGENTS.md

Guidance for coding agents working in this repository. Documentation for library users lives in
`README.md` and `docs/`.

## What this is

`trading-pillar` is a Scala library of broker-agnostic building blocks for trading bots: Telegram
bot plumbing and command handlers, market-data sources (Yahoo Finance via yfinance4s, Tinkoff
Invest via gRPC), price-history sync, a pluggable strategy API with a deterministic backtest
engine, an alert pipeline with anti-spam guards, portfolio analytics, paper trading and a tax
estimate. It defines ports such as storage traits and alert channels, and leaves persistence,
configuration, credentials and user strategies to the applications that consume it.

The artifact is `st.btu %% trading-pillar % 0.1.0-SNAPSHOT`. Consumers build against a locally
published snapshot, so run `sbt publishLocal` after a change they need to see.

## Build and verify

- JDK 21, sbt 1.9.8 (`project/build.properties`), Scala 2.13.18 compiled with `-Xsource:3`.
- Every dependency resolves from Maven Central; versions live in `project/Dependencies.scala`.
- `-Xfatal-warnings` is on, together with `-deprecation` and `-feature`: any warning fails the build.
- Stubs for the Tinkoff API are generated from `src/main/protobuf` during compilation.

```bash
sbt compile
sbt test                                  # whole suite, no network access needed
sbt "testOnly *BacktestEngineSpec"        # one spec
sbt scalafmtAll                           # format; CI runs scalafmtCheckAll
sbt clean coverage test coverageReport    # report in target/scala-2.13/scoverage-report
sbt publishLocal
```

CI (`.github/workflows/ci.yml`) runs `scalafmtCheckAll`, `compile`, `test`, `coverageReport` and a
compile with `-Werror`. A change is ready when `sbt scalafmtCheckAll test` passes locally.

## Layout

Package root `st.btu.trading.pillar`:

| Package | Contents |
|---|---|
| `models` | `domain` (accounts, commands, Tinkoff operations and `OperationType`), `market` (`Ticker`, `Bar`, `Quote`, `Source`, `SourceSetting`, `BrokerInstrument`, `MarketDataError`, `Prices`), `tg`, `tcs`, `binance` |
| `gateways` | `TgGateway`, `MarketDataSource`, the `MarketDataSources` router and the `InstrumentDirectory` port; adapters in `yahoo`, `tcs` (market data by board, instrument directory), `news`, `screener` |
| `facades` | Tinkoff gRPC client (`TcsFacade`, `TcsGrpc`) |
| `strategy` | `Strategy`, `ParameterizedStrategy`, `StrategyRegistry`, `BarHistory`, `indicators`; `backtest` (engine, metrics, sweeps, walk-forward); `reference` (example strategies only) |
| `alerts` | rules, `AlertEngine`, the `AlertDispatcher` guard chain, channels, live strategy evaluation, `SystemAlerts` |
| `analytics` | return, performance and risk functions; `portfolio` (valuation, positions, allocation, rebalancing) |
| `paper`, `tax`, `charts` | paper broker; FIFO tax lots and the annual estimate; equity-curve PNG |
| `services` | sync and command services; `commands` (one handler per Telegram command); `analysis` |
| `processes` | long-running fs2 loops: Telegram updates, portfolio sync, price-history sync, analytics |
| `storages` | persistence ports that consumers implement |
| `data`, `security` | process context, UTC clock, trace ids; HMAC and AES helpers |

Tests mirror this tree under `src/test/scala`. In-memory fakes for every port live in `testkit`.

## Design rules

- **Library, not application.** No credentials, configuration files, persistence or
  user-specific strategies belong here. `strategy.reference` holds examples and test fixtures;
  consumers implement `Strategy` in their own code and register it in a `StrategyRegistry`.
- **Unaware of its consumers.** Code, tests and docs never name or describe a particular
  application that uses the library, its classes, configuration or database schema. Speak of
  consumers in general; table names appear only as a suggested layout for implementers.
- **Ports and adapters.** A storage or channel is a `trait Xxx[F[_]]` with only abstract members,
  documented on the trait. Its test double is a `Ref`-backed fake in `testkit`.
- **Pure cores stay pure.** `BacktestEngine`, `Indicators`, `Returns`, `Performance`, `Risk`,
  `TaxLots`, `TaxReport`, `Rebalancer` and `AlertEngine` take no effect type and are
  deterministic. Effects live in the services, processes and adapters around them.
- **Wiring.** Implementations expose `create[I[_], F[_]](...)(implicit logs: Logs[I, F]): I[...]`
  (tofu logging), or a `resource` returning `Resource[I, ...]` when something must be released.
- **Processes** are fs2 streams. Self-restarting loops use `ProcessLoop.restarting`, which logs a
  crash, raises a `process-crashed:<name>` system alert and restarts after the backoff. Take a
  fresh `TraceIds.next` per iteration and read "now" and "today" through `UtcClock`.
- **Errors** are sealed ADTs extending `Exception`, raised in `F` (`MarketDataError`) or returned
  as `Either` from pure code (`BacktestError`).
- **Time and money.** Instants are UTC; bar dates are local to the exchange's time zone. Prices are
  `BigDecimal` at `Prices.Scale`. Divide with `Indicators.divide`, and round figures computed
  through `Double` to `analytics.ReportScale`.
- **Strategy semantics.** A signal on bar t fills at the open of bar t+1, adjusted for slippage and
  commission; long only, whole shares. `onBar` runs once `warmupBars` of history exist, and that
  history includes the current bar. `proximity` receives the live bar, which is not in the history
  yet.
- **Telegram output** uses legacy Markdown. Send command replies through `Replies.reply`, which
  sanitizes and caps the text; alert notifications pass through the same sanitizer.
- **Persisted values are contracts.** Consumers store the circe encodings of backtest results and
  of `Proximity` and `QuietHours`, the string values of enumeratum enums such as `Source`,
  `AlertKind` and `BindingMode`, and the names of `Side` and `PriceCrossed.Direction`. Renaming any
  of them breaks stored data.
- **Public API.** Every public type and member gets Scaladoc with a one-sentence summary; internals
  are `private` or `private[pillar]`. Narrowing or removing a public member breaks consumers, so
  propose it rather than doing it as part of another change.
- **Vendored protos.** `src/main/protobuf` holds the upstream Tinkoff Invest contracts. Leave their
  upstream text as it is, and record any deviation in the file header, as `marketdata.proto` does.

## Code style

- Formatting follows `.scalafmt.conf` (line length 100). Format the files you change.
- `.as(x)` instead of `.map(_ => x)`, including on `Option`.
- Import with a wildcard (`pkg.*`) when taking five or more members from one package, and list
  them below five. Resolve a clash with a rename: `{Ticker as YfTicker, *}`.
- Constants are UpperCamelCase, in the companion or package object that owns the concept, at the
  narrowest scope. Values that differ per deployment go into the config case classes consumers
  pass in, such as `PriceHistorySyncConfig` or `TcsMarketDataConfig`.
- Closed sets are sealed traits or enumeratum enums, matched exhaustively without `case _`.
  Identifiers and codes get newtypes (`Ticker`, `Figi`, `AccountId`), and no `Boolean` parameter
  selects behaviour.
- Comment only what the code cannot say: a reason, an invariant, an external constraint. No
  narration and no change history, and no references to plans, tickets or work items; those belong
  in commit messages.
- Prose in comments and docs uses the ASCII hyphen, never em or en dashes. The vendored protos are
  exempt.

## Tests

- specs2 `mutable.Specification`; mix in `cats.effect.testing.specs2.CatsEffect` when examples
  return `IO`.
- Shape: `"Subject" >> { "method or capability" >> { "outcome in domain language" >> { ... } } }`.
  Identifiers go in context headings and behaviour in the leaves. No "should", no exception class
  names, no call counts, one behaviour per example, and error paths grouped under their own
  context.
- Test this project's logic: transformations, conditionals, orderings, boundaries and error paths.
  Do not test library behaviour such as derived codecs or enum lookups, or that a value equals the
  literal it was built from.
- Expected values come from a hand-worked example or a known answer, never from re-running the
  production formula.
- Persisted and wire formats are tested against literal examples, so a renamed field fails a test.
- Fakes allocate their state inside `IO`, expose only what specs assert on, and use separate
  constructors rather than flags. Tests never touch the network.

## Documentation

`docs/` is the behavioural reference: `market-data.md`, `strategies.md`, `backtesting.md`,
`analytics.md`, `alerts.md`, `paper-trading.md`, `tax.md`, `telegram-commands.md` and
`storage-contracts.md`. Update the matching page in the same change as the behaviour, signature or
command it describes. The quick-start snippets in `README.md` must compile: paste a changed
snippet into a temporary file under `src/test/scala`, run `sbt Test/compile`, then delete the file.
