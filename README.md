# trading-pillar

Reusable, broker-agnostic building blocks for trading bots: Telegram bot plumbing, market-data
gateways, a pluggable strategy/backtesting engine, and a spam-resistant alert pipeline. A consumer
application wires these pieces together and owns its strategies, storage implementations and
configuration; nothing account- or credential-specific lives here.

## Overview

trading-pillar gives you:

- **Telegram bot plumbing** - long-polling updates, a command router, and a small set of built-in
  commands, extensible with your own `CommandHandler`s.
- **Market data** - a `MarketDataSource` gateway abstraction with a Yahoo Finance implementation
  (via [yfinance4s](https://github.com/coinductive/yfinance4s)), plus a price-history sync process
  that backfills and incrementally updates OHLCV bars for a watchlist.
- **A pluggable strategy API** - a `Strategy` trait with a pure `onBar` decision function, a
  `StrategyRegistry` consumers register their own strategies into at startup, and a handful of
  reference strategies shipped as examples/tests only.
- **A deterministic backtest engine** - streams stored bars through a `Strategy`, fills orders at
  the next bar's open (no look-ahead), and computes a fixed set of performance metrics.
- **An alert pipeline** - evaluates price-threshold rules and live strategy signals, and delivers
  them through a channel (Telegram today) behind a six-guard anti-spam chain (mute, cooldown,
  hysteresis, dedup, daily cap, quiet hours).
- **Existing broker integrations** - Tinkoff Invest (gRPC: portfolio, operations) and a Binance
  gateway (HMAC-SHA256 signed HTTP; not yet wired to a sync process).

None of this is a runnable application by itself. A consumer application wires these pieces
together, implements the storage traits (see [`docs/storage-contracts.md`](docs/storage-contracts.md))
and owns its strategies; the [Quick start](#quick-start) shows the wiring and how a consumer
strategy is registered.

## Module map

| Package | Contents |
|---|---|
| `models.domain` | `Account`, `Command`, `Portfolio`, `TcsPortfolioOperation`, newtypes (`AccountId`, `Figi`, ...) |
| `models.market` | `Ticker`, `Bar`, `Quote`, `InstrumentInfo`, `Source` (Yahoo/Tinkoff/Binance), `SourceSetting`/`TickerSource` (the source a ticker uses, with its Tinkoff board), `BrokerInstrument`, `WatchlistItem`, `MarketDataError` |
| `models.tg`, `models.tcs`, `models.binance` | Telegram, Tinkoff and Binance wire/domain types |
| `gateways` | `TgGateway` (Telegram Bot API), `BinanceGateway`, `MarketDataSource` (per-broker data) with `MarketDataSources`/`MarketDataSourcesImpl` routing a ticker to the source its setting selects, `InstrumentDirectory` (broker FIGI to instrument), `gateways.yahoo` (Yahoo Finance via yfinance4s), `gateways.tcs` (Tinkoff Invest market data and instrument directory, gRPC) |
| `gateways.screener` | `Screener`, `YahooScreener`, `PredefinedScreen` (gainers/losers/most-active) - backs `/screen` |
| `gateways.news` | `NewsSource`, `YahooNewsSource`, `NewsItem` - per-ticker headlines, backs `/news` |
| `facades` | `TcsFacade` - high-level Tinkoff gRPC API (portfolio, operations) |
| `strategy` | `Strategy`, `StrategyContext`, `BarHistory`, `StrategyRegistry`, `StrategySignal`/`Sizing`, `indicators.Indicators` (SMA/EMA/RSI/ATR) |
| `strategy.backtest` | `BacktestEngine`, `BacktestConfig`, `BacktestResult`/`BacktestMetrics`, `BacktestService`, `BacktestSweep`/`Grid` (parameter sweeps), `WalkForward`, `BacktestRunStorage` |
| `strategy.reference` | `MovingAverageCrossoverStrategy`, `RsiReversionStrategy` - **examples/tests only**, not for production use as-is |
| `charts` | `EquityCurveImage` - renders a `BacktestResult`'s equity curve as a PNG, sent alongside `/backtest`'s reply |
| `analytics` | `Returns`, `Valuation`, `Performance` (total/annualized/time-weighted/money-weighted return), `Risk` (volatility, Sharpe, Sortino, max drawdown, VaR, beta), `FxRates` - pure, no `F[_]`; see [`docs/analytics.md`](docs/analytics.md) |
| `analytics.portfolio` | `PositionValue`/`PositionSnapshot`, `Allocation` (breakdown by instrument type/currency/exchange), `AllocationTarget`, `Rebalancer` (drift and buy/sell suggestions), `PortfolioValuationService` - backs `/allocation`, `/drift`, `/rebalance`, `/portfolio` |
| `alerts` | `Alert`, `AlertKind`, `AlertRule` (`PriceCrossed`, `StrategyBinding`), `Proximity`, `QuietHours`, `DeliveryPolicy`, `AlertEngine`, `AlertDispatcher`, `LiveStrategyService`, `AlertProcess`, `TelegramAlertChannel`, `FailureTracker` (consecutive-failure counter), `SystemAlerts` (infrastructure alerts, e.g. a crashed process loop or a sync failure) |
| `paper` | `PaperBroker`/`PaperBrokerImpl`, `PaperAccount`/`PaperPosition`/`PaperOrder`, `PaperBrokerConfig` - simulated order execution for a paper-mode `StrategyBinding`; see [`docs/paper-trading.md`](docs/paper-trading.md) |
| `tax` | `TaxService`/`TaxServiceImpl`, `TaxLots` (FIFO lot matching), `TaxReport` (annual gain/loss estimate), `Lot`, `RealizedGain`, `AnnualTaxReport`, `TaxConfig`; see [`docs/tax.md`](docs/tax.md) |
| `services` | `CommandHandleService`, `PriceHistorySyncService`, `TcsPortfolioService` |
| `services.analysis` | `AnalysisService`/`AnalysisServiceImpl`, `StockAnalysis` - a per-ticker technical snapshot (SMA/RSI/52-week range/dividend yield); backs `/analyze` and `/compare` |
| `services.commands` | `CommandParser`, `CommandHandler`, and the command handlers, in the order `/help` lists them: ping/info built in, then watchlist/source/strategies/backtest/sweep/alerts/alert/mute/unmute/dividends/performance/portfolio/positions/allocation/drift/rebalance/paper/news/screen/tax/lots/export/analyze/compare/menu as `extraHandlers`, then help (see [`docs/telegram-commands.md`](docs/telegram-commands.md)) |
| `processes` | `TgUpdatesProcess`, `TcsPortfolioSyncProcess`, `PriceHistorySyncProcess`, `AnalyticsProcess` (recomputes portfolio valuations and dispatches allocation-drift alerts) - FS2 streams a consumer runs under `parJoin` |
| `data` | `ProcessContext`, `TraceIds`, `UtcClock` - the trace/account context threaded through every process via `tofu.WithLocal`, and a UTC-normalized clock helper |
| `storages` | Storage traits a consumer implements; see [`docs/storage-contracts.md`](docs/storage-contracts.md) |
| `security` | AES-256 (OpenSSL-compatible) and HMAC-SHA256 helpers |

Full docs: [market data](docs/market-data.md) · [strategies](docs/strategies.md) ·
[backtesting](docs/backtesting.md) · [analytics](docs/analytics.md) · [alerts](docs/alerts.md) ·
[paper trading](docs/paper-trading.md) · [tax](docs/tax.md) ·
[Telegram commands](docs/telegram-commands.md) · [storage contracts](docs/storage-contracts.md)

## Quick start

The snippets below compile against the real `trading-pillar` API. Most `create`/`resource`
constructors take two effects: the one a component is built in (`I`) and the one it runs in (`F`),
with a `tofu.logging.Logs[I, F]` in scope; a service that logs nothing, such as
`PaperBrokerImpl.create[F]`, takes only `F`. The snippets build in `cats.effect.IO` and run in
`ReaderT[IO, ProcessContext, *]`, because the processes use `WithLocal[F, ProcessContext]` to give
each iteration a fresh trace id and contextual logging attaches that context to every log entry. A
consumer can use any other `I`/`F` pair that satisfies the constructors' type-class bounds and has a
`Logs[I, F]`, for example a ZIO runtime through zio-interop-cats.

The snippets share this setup, which defines `F` and the instances the constructors ask for:

```scala
import cats.data.ReaderT
import cats.effect.IO
import tofu.Delay
import tofu.generate.GenUUID
import tofu.logging.{LoggableContext, Logs}
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.models.domain.AccountId

type F[A] = ReaderT[IO, ProcessContext, A]

// tofu-logging needs a Delay for both effects. With tofu-core-ce3 on the classpath they come from
// Sync; without it, write IO's by hand and tofu derives the ReaderT's.
implicit val delayForIO: Delay[IO] = new Delay[IO] { def delay[A](a: => A): IO[A] = IO(a) }

// Every log entry carries the process context of the call that wrote it. tofu's logback layouts
// (ConsoleContextLayout and ELKLayout, in tofu-logging-layout) render it.
implicit val loggableContext: LoggableContext[F] = LoggableContext.of[F].instance[ProcessContext]
implicit val logs: Logs[IO, F] = Logs.withContext[IO, F]
implicit val genUUID: GenUUID[F] = GenUUID.syncGenUUID[F]

// The context F starts from; each process iteration sets its own trace id.
val root = ProcessContext(traceId = "", accountId = AccountId(1))
```

### a) Wire market-data sources behind a router, and the price-history sync process

```scala
import scala.concurrent.duration.*
import st.btu.trading.pillar.alerts.{AlertDispatcherImpl, FailureTrackerImpl, SystemAlertsImpl}
import st.btu.trading.pillar.gateways.MarketDataSourcesImpl
import st.btu.trading.pillar.gateways.yahoo.{YahooApi, YahooMarketDataSource}
import st.btu.trading.pillar.gateways.yahoo.YahooMarketDataSource.YahooConfig
import st.btu.trading.pillar.models.market.SourceSetting
import st.btu.trading.pillar.processes.PriceHistorySyncProcessImpl
import st.btu.trading.pillar.services.{PriceHistorySyncConfig, PriceHistorySyncServiceImpl}
import st.btu.trading.pillar.storages.*

def wireSync(
  priceHistoryStorage: PriceHistoryStorage[F], // your implementation; the library ships none
  instrumentStorage: InstrumentStorage[F],
  dividendStorage: DividendStorage[F],
  watchlistStorage: WatchlistStorage[F],
  tickerSourceStorage: TickerSourceStorage[F],
  alertHistoryStorage: AlertHistoryStorage[F]
): IO[Unit] = {
  val yahooConfig = YahooConfig(
    connectTimeout = 10.seconds,
    readTimeout = 30.seconds,
    retries = 3,
    maxRequestsPerSecond = 2
  )
  val syncConfig = PriceHistorySyncConfig(
    interval = 6.hours,
    backfillYears = 2,
    overlapDays = 5,
    rateLimitBackoff = 60.seconds,
    metadataRefresh = 7.days
  )

  // The client is built in F; applying `root` turns its resource into one in IO.
  YahooMarketDataSource.clientResource[F](yahooConfig).mapK(ReaderT.applyK(root)).use { client =>
    val yahooApi = YahooApi.fromClient[F](client)
    for {
      yahooSource <- YahooMarketDataSource.create[IO, F](yahooApi)
      // Routes each ticker to the adapter its stored source setting selects, and a ticker with no
      // setting to Yahoo. Pass the Tinkoff adapter (a TcsMarketDataSource, for MOEX symbols) as
      // the second argument once it is wired.
      sources = new MarketDataSourcesImpl[F](
        Some(yahooSource),
        None,
        tickerSourceStorage,
        SourceSetting.Yahoo
      )
      // A sync failure is infrastructure-level, not per-symbol, so it is raised through the same
      // dispatcher/channels as any other alert; an empty channel list here still records it in
      // AlertHistoryStorage, just without delivery - see snippet (d) for a real Telegram channel,
      // which a real app shares with this one rather than building twice.
      dispatcher <- AlertDispatcherImpl.create[IO, F](List.empty, alertHistoryStorage)
      systemAlerts <- SystemAlertsImpl.create[IO, F](dispatcher, channels = List.empty)
      failureTracker <- FailureTrackerImpl.create[IO, F]
      syncService <- PriceHistorySyncServiceImpl.create[IO, F](
        sources,
        priceHistoryStorage,
        instrumentStorage,
        dividendStorage,
        syncConfig
      )
      syncProcess <- PriceHistorySyncProcessImpl
        .create[IO, F](watchlistStorage, syncService, syncConfig, systemAlerts, failureTracker)
      // normally run alongside your other processes via parJoin
      _ <- syncProcess.run.compile.drain.run(root)
    } yield ()
  }
}
```

`watchlistStorage.add(WatchlistItem(accountId, Ticker("AAPL"), enabled = true, None, now))` (e.g.
from the `/watchlist add` command) is what puts a symbol in scope for the sync loop. Which source
serves it is a setting of the ticker, kept in `TickerSourceStorage` (e.g. by `/source SBER tinkoff`),
and Yahoo here when none is stored. A sync failure raises a `sync-failed:<ticker>` system alert
after 3 consecutive failures, and a matching `sync-recovered:<ticker>` once it succeeds again.

### b) Implement and register your own `Strategy`

User strategies **never** live in trading-pillar - only two reference strategies do, as
examples/tests (`strategy.reference`). A consumer implements `Strategy` in its own codebase. The
example below composes the library's reference crossover strategy, because that class is `final`:

```scala
package com.example.strategies

import st.btu.trading.pillar.models.market.Bar
import st.btu.trading.pillar.strategy.reference.MovingAverageCrossoverStrategy
import st.btu.trading.pillar.strategy.*

final class MaCross50x200 extends Strategy {
  private val delegate = new MovingAverageCrossoverStrategy(50, 200)

  def id: StrategyId = delegate.id
  def name: String = delegate.name
  def parameters: Map[String, String] = delegate.parameters
  def warmupBars: Int = delegate.warmupBars
  def onBar(ctx: StrategyContext, bar: Bar): List[StrategySignal] = delegate.onBar(ctx, bar)
  override def proximity(ctx: StrategyContext, bar: Bar): Option[PreSignal] =
    delegate.proximity(ctx, bar)
}
```

A strategy written directly (not by composition) just implements `onBar` - see
[`docs/strategies.md`](docs/strategies.md) for the full contract. Register it once at startup:

```scala
import com.example.strategies.MaCross50x200
import st.btu.trading.pillar.strategy.StrategyRegistryImpl

for {
  registry <- StrategyRegistryImpl.create[IO, F]
  _        <- registry.register(new MaCross50x200).run(root)
} yield registry
```

### c) Run a backtest

Through `BacktestService` (loads bars from `PriceHistoryStorage` and persists the run):

```scala
import java.time.LocalDate
import st.btu.trading.pillar.models.market.{Source, Ticker}
import st.btu.trading.pillar.strategy.{StrategyId, StrategyRegistry}
import st.btu.trading.pillar.strategy.backtest.{BacktestConfig, BacktestServiceImpl}
import st.btu.trading.pillar.storages.BacktestRunStorage

def runBacktest(
  registry: StrategyRegistry[F],
  priceHistoryStorage: PriceHistoryStorage[F],
  backtestRunStorage: BacktestRunStorage[F]
): IO[Unit] = {
  val config = BacktestConfig(initialCash = 100000, commissionRate = BigDecimal("0.0005"), slippageBps = 5)

  for {
    backtestService <- BacktestServiceImpl.create[IO, F](
      registry,
      priceHistoryStorage,
      backtestRunStorage
    )
    run <- backtestService
      .run(
        StrategyId("ma-cross-50-200"),
        Ticker("AAPL"),
        Source.Yahoo,
        LocalDate.now.minusYears(2),
        LocalDate.now,
        config
      )
      .run(root)
    _ = println(s"total return ${run.result.metrics.totalReturn}, trades ${run.result.trades.size}")
  } yield ()
}
```

Or drive the pure engine directly with in-memory bars (no storage, no `F[_]` at all):

```scala
import st.btu.trading.pillar.strategy.backtest.BacktestEngine

BacktestEngine.run(myStrategy, bars, config) match {
  case Right(result) => println(result.metrics)
  case Left(error)   => println(s"backtest failed: ${error.message}")
}
```

Same via Telegram once a strategy is registered: `/backtest ma-cross-50-200 AAPL 2`.

### d) Bind a strategy to a symbol, receive Telegram alerts through the guard chain, and paper-trade it

A `StrategyBinding` (supplied by the consumer through its `StrategyBindingStorage`, which the library
only reads) is what `AlertProcess` polls; a binding in paper mode additionally executes every
Buy/Sell signal through a `PaperBroker`. Wiring the delivery and paper-execution sides:

```scala
import scala.concurrent.duration.*
import sttp.client3.SttpBackend
import st.btu.trading.pillar.alerts.*
import st.btu.trading.pillar.gateways.{MarketDataSources, TgGatewayImpl}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.paper.{PaperBrokerConfig, PaperBrokerImpl}
import st.btu.trading.pillar.storages.{
  AlertHistoryStorage,
  AlertRuleStorage,
  PaperStorage,
  StrategyBindingStorage
}

def wireAlerts(
  sttpBackend: SttpBackend[F, Any],
  strategyBindingStorage: StrategyBindingStorage[F],
  alertRuleStorage: AlertRuleStorage[F],
  alertHistoryStorage: AlertHistoryStorage[F],
  priceHistoryStorage: PriceHistoryStorage[F],
  paperStorage: PaperStorage[F], // backs `/paper` and any paper-mode StrategyBinding
  marketDataSources: MarketDataSources[F], // e.g. the `sources` router built in snippet (a)
  registry: StrategyRegistry[F]
): IO[Unit] =
  for {
    tgGateway    <- TgGatewayImpl.create[IO, F](tg.Token("<bot-token>"), sttpBackend)
    channel      <- TelegramAlertChannel.create[IO, F](tgGateway, tg.ChatId(123456789L))
    dispatcher   <- AlertDispatcherImpl.create[IO, F](List(channel), alertHistoryStorage)
    systemAlerts <- SystemAlertsImpl.create[IO, F](dispatcher, channels = List("telegram"))
    liveSvc      <- LiveStrategyServiceImpl.create[IO, F](priceHistoryStorage, marketDataSources)
    paperBroker = PaperBrokerImpl.create[F](
      paperStorage,
      PaperBrokerConfig(commissionRate = BigDecimal("0.0005"), slippageBps = 5)
    )
    process <- AlertProcessImpl.create[IO, F](
      strategyBindingStorage,
      alertRuleStorage,
      registry,
      liveSvc,
      marketDataSources,
      priceHistoryStorage,
      dispatcher,
      paperBroker,
      systemAlerts,
      AlertProcessConfig(pollInterval = 5.minutes, rateLimitBackoff = 60.seconds)
    )
    _ <- process.run.compile.drain.run(root)
  } yield ()
```

Every alert `dispatcher.dispatch` emits goes through six guards in order - mute, cooldown,
hysteresis, dedup, daily cap, quiet hours - before delivery; a suppressed alert is still recorded
in `AlertHistoryStorage` with `suppressedBy` set. See [`docs/alerts.md`](docs/alerts.md) for the
exact order and each guard's semantics. Paper execution is independent of those guards - a
paper-mode binding trades on every signal regardless of whether its matching alert was delivered,
muted or suppressed; see [`docs/paper-trading.md`](docs/paper-trading.md) for the four reasons a
paper order can be rejected instead of filled.

## Build

- **JDK 21**
- **Scala 2.13.18**, built with **sbt 1.9.8**

```bash
sbt test          # specs2 + cats-effect-testing; no network access required
sbt publishLocal   # publishes st.btu %% trading-pillar % 0.1.0-SNAPSHOT to ~/.ivy2/local
```

A consumer application then depends on that local artifact; run `sbt publishLocal` again after any
trading-pillar change the consumer needs to see.

## Status

- Telegram bot plumbing and the Tinkoff Invest portfolio-operations sync: used in production by an
  earlier version of this library.
- Market data (Yahoo and Tinkoff, routed per ticker by `MarketDataSources`), strategies and
  backtesting (multi-symbol runs, parameter sweeps, walk-forward validation), alerts and system
  alerts, analytics, dividends, paper trading and the tax-estimate report: implemented and covered
  by the test suite, but not yet used in production; the parts that call Yahoo or Tinkoff have run
  only against test fakes. Yahoo Finance no longer serves MOEX (Russian exchange) data; MOEX symbols
  (e.g. `SBER`) are served instead through `TcsMarketDataSource` (Tinkoff Invest gRPC) - see
  [`docs/market-data.md`](docs/market-data.md).
- Binance: a minimal gateway, one signed `allOrders` request returning raw JSON. No process uses it,
  and the gateway has no tests.
