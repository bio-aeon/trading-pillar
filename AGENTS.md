# AGENTS.md

Guidance for coding agents working in this repository. Documentation for library users lives in
`README.md` and `docs/`.

## What this is

`trading-pillar` is a Scala library of building blocks for trading bots: Telegram bot plumbing and
command handlers, market-data sources (Yahoo Finance via yfinance4s, Tinkoff Invest via gRPC,
Binance's public REST API),
price-history sync, a pluggable strategy API with a deterministic backtest engine, an alert
pipeline with anti-spam guards, portfolio analytics, paper trading and a tax estimate. It defines
ports such as storage traits and alert channels, and leaves persistence, configuration,
credentials and user strategies to the applications that consume it.

Strategies, backtests, alerts and the analytics math don't depend on a broker; the portfolio side
reads the broker-neutral `AccountOperation`s, and only the operations sync and the instrument
directory are a broker's own. The Tinkoff sync runs for each Tinkoff account a consumer gives it and
records every operation as Tinkoff sent it together with its translation into an
`AccountOperation`. The Interactive Brokers sync does the same for each IB account: it reads the
statements of an Activity Flex Query through the Flex Web Service, window by window, and records
every statement row as IB sent it (`IbkrStatementRow`) with its translation, and then reconciles
the positions the translations leave with the Open Positions the statement reports, raising a system
alert while they differ. The legs of a corporate action are interpreted together, as split,
conversion, spin-off, stock dividend or sale operations that share a group, and the tax lots carry
their cost and dates through them; what the sync could not read for certain it raises as a system
alert. The Binance sync reads a spot account through Binance's REST API with a read-only key, which
every run checks, and records each trade, deposit, withdrawal, fiat order, card purchase, Convert
order, dust conversion and distribution as Binance sent it with its translation; trades are read
pair by pair, and a trade between two coins, a fee paid in BNB or a coin credited as a distribution
is valued by the stored close of a one-minute bar. Every run also compares
the balances Binance reports, in the spot and the funding wallet, with the ones the records leave,
and raises a system alert while they differ. The valuation, the tax services and `/export` read those
translations, and an instrument is looked up by its `InstrumentKey`, the broker and the id that
broker gives it, through a directory per broker. The price-history sync starts every cycle by putting
the instruments each account holds on its watchlist, with the source setting its broker's pricing
rule gives, and the rate tickers that convert the currencies of its operations and of the costs its
owner entered for transfers in; a Binance coin is priced from Binance's own daily bars while the
router has a Binance adapter, and stays off the watchlist otherwise. The tax lots keep the currency
of their cost at every broker, and a sale converts each cost on the day of the sale. A futures
contract is valued at zero, since the broker settles its gain or loss into cash as variation
margin: `BrokerInstrument.isFuture` says which instruments are futures, the valuation counts the
margin as cash and a return, the tax lots leave futures out, and `/tax` reports the year's margin
less the fees charged on futures as the futures result, part of the taxable gains. At Tinkoff the
buy or sell of a futures contract has an amount of zero, whatever payment the broker reports. A
future's record also carries its `FutureTerms`, which only `TcsInstrumentDirectory` writes and
refreshes once a day: the value of a point and the expiration date. A position shows its exposure
as a `FutureExposure`, the day change counts a future's price move through the point value, and a
contract that `BrokerInstrument.expiredOn` says has ended is neither a position of a snapshot nor a
held instrument.
`Source` is a closed set of data sources, so supporting another broker's data means changing the
library.

The artifact is `st.btu %% trading-pillar % 0.1.0-SNAPSHOT`. Consumers build against a locally
published snapshot, so run `sbt publishLocal` after a change they need to see.

## Build and verify

- JDK 21, sbt 1.9.8 (`project/build.properties`), Scala 3.9.0.
- Every dependency resolves from Maven Central; versions live in `project/Dependencies.scala`.
- `-Werror` is on, together with `-deprecation` and `-feature`: any warning fails the build. Only
  the warnings of the generated Tinkoff stubs are filtered out.
- Stubs for the Tinkoff API are generated during compilation from the official contracts,
  `ru.tinkoff.piapi:java-sdk-grpc-contract`: sbt unpacks its `.proto` files (`protobuf-src`) and
  ScalaPB compiles them. The jar's own Java classes are not used. The contracts import Google's
  `field_behavior.proto`; `src/main/protobuf/google/api/package.proto` generates its code into
  `st.btu.trading.pillar.contracts`, so it cannot clash with another library's `com.google.api`.

```bash
sbt compile
sbt test                                  # whole suite, no network access needed
sbt "testOnly *BacktestEngineSpec"        # one spec
sbt scalafmtAll                           # format; CI runs scalafmtCheckAll
sbt clean coverage test coverageReport    # report in target/scala-3.9.0/scoverage-report
sbt publishLocal
```

CI (`.github/workflows/ci.yml`) runs `scalafmtCheckAll`, `compile`, `test`, `coverageReport` and a
compile with `-Werror`. A change is ready when `sbt scalafmtCheckAll test` passes locally.

## Layout

Package root `st.btu.trading.pillar`:

| Package | Contents |
|---|---|
| `models` | `Account`, `HttpProxy`; `domain` (`Broker`, `AccountOperation` with its kinds and states, commands, Tinkoff operations and `OperationType`), `market` (`Ticker`, `Bar`, `Quote`, `Source`, `SourceSetting`, `BrokerInstrument` with its `FutureTerms`, `MarketDataError`, `Prices`), `tg`, `tcs`, `ibkr` (`IbkrAccount`, `FlexToken`, `IbkrStatementRow`, `IbkrSection`, `IbkrStatement`), `binance` (`BinanceAccount`, `ApiKey`, `SecretKey`, `BinanceRecord`, `BinanceSource`, `BinancePair`, `BinanceBalances`, `BinanceDailyBar`, `BinanceRecordError`) |
| `gateways` | `TgGateway`, `CapturingTgGateway`, `MarketDataSource`, the `MarketDataSources` router, the `InstrumentDirectory` port, its `RoutingInstrumentDirectory` router and `StoredInstrumentDirectory` for a broker with no lookup, Interactive Brokers and Binance; adapters in `yahoo`, `tcs` (market data by board, instrument directory), `ibkr` (the Flex Web Service client, the statement parser), `binance` (the keyless `BinanceMarket` client and the keyed `BinanceApi` one over a shared transport, with their signing, pacing and errors, and `BinanceMarketDataSource`), `news`, `screener` |
| `facades` | Tinkoff gRPC client (`TcsFacade`, `TcsGrpc`) |
| `strategy` | `Strategy`, `ParameterizedStrategy`, `StrategyRegistry`, `BarHistory`, `indicators`; `backtest` (engine, metrics, sweeps, walk-forward); `reference` (example strategies only) |
| `alerts` | rules, `AlertEngine`, the `AlertDispatcher` guard chain, channels, live strategy evaluation, `SystemAlerts` |
| `analytics` | return, performance and risk functions; `portfolio` (valuation, `Holdings`, positions, allocation, rebalancing, performance metrics, periods, snapshots) |
| `paper`, `tax`, `charts` | paper broker; FIFO tax lots that keep the currency of their cost, carried through corporate actions, with the entered `TransferCost`s of transfers in and no lots for futures, and the annual estimate with its futures result; equity-curve PNG |
| `services` | sync and command services (`TcsPortfolioService`, `IbkrSyncService` with the pure `IbkrRowTranslation` and `IbkrCorporateActions`, `IbkrReconciler` over the pure `IbkrReconciliation`, `BinanceSyncService` with the pure `BinanceTranslation`, `BinancePrices` and `BinanceInstruments`, `BinanceReconciler` over the pure `BinanceReconciliation`, `HeldInstrumentsService` with its pricing rules `HeldInstrumentPricing` and `IbkrYahooSymbols` and the `RateTickerConfig` of its rate tickers); `commands` (one handler per Telegram command, `CommandHandlers.standard`, `CommandEffect`, `CommandRunner`); `analysis` |
| `processes` | long-running fs2 loops: Telegram updates, portfolio sync (Tinkoff and Interactive Brokers, the latter reconciling positions), the Binance records sync, price-history sync (adding held instruments first), analytics |
| `mcp` | MCP server for AI agents: `McpTools` over the storages, market data sources and command handlers, `McpToolBuilder` (the conventions every tool follows), `McpToolServer`, `McpHttpServer` behind a bearer token, `EffectBridge` |
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
  `Holdings`, `TaxLots`, `TaxReport`, `Rebalancer` and `AlertEngine` take no effect type and are
  deterministic. Effects live in the services, processes and adapters around them.
- **Wiring.** Implementations expose `create[I[_], F[_]](...)(implicit logs: Logs[I, F]): I[...]`
  (tofu logging), or a `resource` returning `Resource[I, ...]` when something must be released.
  Evidence beyond a plain type class of `F`, such as `Lift[F, I]` or
  `WithLocal[F, ProcessContext]`, is an implicit parameter next to `logs`, not a context bound.
- **Processes** are fs2 streams. Self-restarting loops use `ProcessLoop.restarting`, which logs a
  crash, raises a `process-crashed:<name>` system alert and restarts after the backoff. Take a
  fresh `TraceIds.next` per iteration and read "now" and "today" through `UtcClock`.
- **Errors** are sealed ADTs extending `Exception`, raised in `F` (`MarketDataError`, `FlexError`,
  `BinanceError`, `BinanceKeyError`) or returned as `Either` from pure code (`BacktestError`,
  `IbkrStatementError`, `BinanceRecordError`).
- **Time and money.** Instants are UTC; bar dates are local to the exchange's time zone. Prices are
  `BigDecimal` at `Prices.Scale`. Divide with `Indicators.divide`, and round figures computed
  through `Double` to `analytics.ReportScale`.
- **Strategy semantics.** A signal on bar t fills at the open of bar t+1, adjusted for slippage and
  commission; long only, whole shares. `onBar` runs once `warmupBars` of history exist, and that
  history includes the current bar. `proximity` receives the live bar, which is not in the history
  yet.
- **Telegram output** uses legacy Markdown. Send command replies through `Replies.reply`, which
  sanitizes and caps the text; alert notifications pass through the same sanitizer. A price in a
  reply goes through `Replies.price`, which keeps the digits of a coin priced below a cent; a
  value, cash or total through `Replies.scaled`.
- **Telegram input** comes from the allowed chats only. `TgUpdatesProcessImpl` turns an update into
  a command for its chat and runs it only for a chat in `allowedChats`, answering nothing else; a
  new kind of update goes through the same check.
- **Persisted values are contracts.** Consumers store the circe encodings of backtest results, of
  `Proximity` and `QuietHours`, and of `Allocation` and `PortfolioMetrics`, the string values of
  enumeratum enums such as `Source`, `Broker`, `OperationKind`, `OperationState`, `IbkrSection`,
  `BinanceSource`, `AlertKind`, `BindingMode` and `Period`, and the names of `Side` and
  `PriceCrossed.Direction`. Renaming any of them breaks stored data.
- **Public API.** Every public type and member gets Scaladoc with a one-sentence summary; internals
  are `private` or `private[pillar]`. Narrowing or removing a public member breaks consumers, so
  propose it rather than doing it as part of another change.
- **Brokers are read, never driven.** No client places an order, withdraws or transfers. The
  Binance sync checks its key on every run and refuses one that could do more than read, the balance
  calls only read, and the keyless market client holds no key and cannot sign; keep it that way,
  and keep a key, a signature and a query string out of every log line and error message.
- **Tinkoff contracts.** Updating the T-Invest contracts is a bump of `Versions.investContracts`. A
  new version can add calls, which the gRPC fakes in `testkit.TcsMarketDataFakes` must stub, and can
  deprecate request fields. Build requests with the replacement fields, since naming a deprecated
  field in a constructor raises no warning.
- **Tinkoff TLS.** The API's certificates chain to the Russian Trusted Root CA, which the JDK does
  not ship. `TcsGrpc` trusts it next to the JDK's roots, for that channel only, from
  `src/main/resources/st/btu/trading/pillar/facades/russian-trusted-root-ca.pem`. The root expires
  on 2032-02-27; take a reissued one from the Ministry of Digital Development, and check its
  fingerprint against T-Bank's own SDK.

## Code style

- Formatting follows `.scalafmt.conf` (line length 100). Format the files you change.
- `.as(x)` instead of `.map(_ => x)`, including on `Option`.
- Import with a wildcard (`pkg.*`) when taking five or more members from one package, and list
  them below five. Resolve a clash with a rename: `{Ticker as YfTicker, *}`.
- Constants are UpperCamelCase, in the companion or package object that owns the concept, at the
  narrowest scope. Values that differ per deployment go into the config case classes consumers
  pass in, such as `PriceHistorySyncConfig` or `TcsMarketDataConfig`.
- Closed sets are sealed traits or enumeratum enums, matched exhaustively without `case _`.
  Identifiers and codes get opaque types (`Ticker`, `Figi`, `AccountId`), declared in the package
  object of the package that owns them with `apply`, a `value` extension and their `Show` and
  `Loggable` instances in the companion. No `Boolean` parameter selects behaviour.
- Circe codecs of models are `implicit val codecForX: Codec.AsObject[X] = deriveCodec` in the
  companion (`deriveDecoder` and `deriveEncoder` when `X` has a type parameter).
- Comment only what the code cannot say: a reason, an invariant, an external constraint. No
  narration and no change history, and no references to plans, tickets or work items; those belong
  in commit messages.

## Tests

- specs2 `mutable.Specification`; mix in `cats.effect.testing.specs2.CatsEffect` when examples
  return `IO`.
- Shape: `"Subject" >> { "method or capability" >> { "outcome in domain language" >> { ... } } }`.
  Identifiers go in context headings and behaviour in the leaves. No "should", no exception class
  names, no call counts, one behaviour per example, and error paths grouped under their own
  context.
- Inside a specs2 `Specification`, `x.value` on an opaque type does not resolve, because specs2's
  own `value` conversions are ambiguous. Import the accessor explicitly, for example
  `import st.btu.trading.pillar.models.market.Ticker.value`.
- Give `beSome`, `beRight` and `beLeft` their type argument before `.like`, as in
  `beSome[Quote].like { ... }`: Scala 3 infers `Nothing` otherwise.
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
`analytics.md`, `alerts.md`, `paper-trading.md`, `tax.md`, `ibkr.md`, `binance.md`,
`telegram-commands.md`, `storage-contracts.md` and `mcp.md`. Update the matching page in the same
change as the behaviour, signature or command it describes. The quick-start snippets in `README.md`
must compile: paste a changed snippet into a temporary file under `src/test/scala`, run
`sbt Test/compile`, then delete the file.
Snippets suspend their side effects, as the code does: `IO.println`, not `println`.
