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
| `models.market` | `Ticker`, `Bar`, `Quote`, `InstrumentInfo`, `Source` (Yahoo/Tinkoff/Binance), `WatchlistItem`, `MarketDataError` |
| `models.tg`, `models.tcs`, `models.binance` | Telegram, Tinkoff and Binance wire/domain types |
| `gateways` | `TgGateway` (Telegram Bot API), `BinanceGateway`, `MarketDataSource` (per-broker data) with `MarketDataSources`/`MarketDataSourcesImpl` routing a ticker to the broker that serves it, `gateways.yahoo` (Yahoo Finance via yfinance4s), `gateways.tcs` (Tinkoff Invest market data, gRPC) |
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
| `services.commands` | `CommandParser`, `CommandHandler`, and the command handlers, in the order `/help` lists them: ping/info built in, then watchlist/strategies/backtest/sweep/alerts/alert/mute/unmute/dividends/performance/portfolio/positions/allocation/drift/rebalance/paper/news/screen/tax/lots/export/analyze/compare/menu as `extraHandlers`, then help (see [`docs/telegram-commands.md`](docs/telegram-commands.md)) |
| `processes` | `TgUpdatesProcess`, `TcsPortfolioSyncProcess`, `PriceHistorySyncProcess`, `AnalyticsProcess` (recomputes portfolio valuations and dispatches allocation-drift alerts) - FS2 streams a consumer runs under `parJoin` |
| `data` | `ProcessContext`, `TraceIds`, `UtcClock` - the trace/account context threaded through every process via `tofu.WithLocal`, and a UTC-normalized clock helper |
| `storages` | Storage traits a consumer implements; see [`docs/storage-contracts.md`](docs/storage-contracts.md) |
| `security` | AES-256 (OpenSSL-compatible) and HMAC-SHA256 helpers |

Full docs: [market data](docs/market-data.md) · [strategies](docs/strategies.md) ·
[backtesting](docs/backtesting.md) · [analytics](docs/analytics.md) · [alerts](docs/alerts.md) ·
[paper trading](docs/paper-trading.md) · [tax](docs/tax.md) ·
[Telegram commands](docs/telegram-commands.md) · [storage contracts](docs/storage-contracts.md)

## Quick start

The snippets below compile against the real `trading-pillar` API. They use `cats.effect.IO` for
both the initialization (`I`) and runtime (`F`) effect that every `create`/`resource` constructor
in this library is parameterized over (`tofu.logging.Logs[I, F]`); a consumer can use the same
pattern, or any other `I`/`F` pair that satisfies the constructors' type-class bounds and has a
`Logs[I, F]`, for example a ZIO runtime through zio-interop-cats.

### a) Wire market-data sources behind a router, and the price-history sync process

```scala
import cats.effect.{IO, Ref}
import cats.syntax.functor.*
import cats.Functor
import scala.concurrent.duration.*
import tofu.{Delay, WithLocal}
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}
import st.btu.trading.pillar.alerts.{AlertDispatcherImpl, FailureTrackerImpl, SystemAlertsImpl}
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.gateways.MarketDataSourcesImpl
import st.btu.trading.pillar.gateways.yahoo.{YahooApi, YahooMarketDataSource}
import st.btu.trading.pillar.gateways.yahoo.YahooMarketDataSource.YahooConfig
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Source
import st.btu.trading.pillar.processes.PriceHistorySyncProcessImpl
import st.btu.trading.pillar.services.{PriceHistorySyncConfig, PriceHistorySyncServiceImpl}
import st.btu.trading.pillar.storages.{
  AlertHistoryStorage,
  DividendStorage,
  InstrumentStorage,
  PriceHistoryStorage,
  WatchlistStorage
}

// trading-pillar's tofu-logging usage needs a `Delay[F]` instance; this library does not depend on
// tofu-core-ce3 (which would derive one for any `Sync[F]` automatically), so a consumer using IO
// provides this trivial one itself, once, and shares it the same way as `withLocalIO` below.
implicit val delayForIO: Delay[IO] = new Delay[IO] {
  override def delay[A](a: => A): IO[A] = IO(a)
}

// The simplest way to satisfy `WithLocal[F, ProcessContext]` for a plain IO program; another
// runtime can provide its own instance.
def withLocalIO(initial: ProcessContext): IO[WithLocal[IO, ProcessContext]] =
  Ref.of[IO, ProcessContext](initial).map { ref =>
    new WithLocal[IO, ProcessContext] {
      def functor: Functor[IO] = Functor[IO]
      def context: IO[ProcessContext] = ref.get
      def local[A](fa: IO[A])(project: ProcessContext => ProcessContext): IO[A] =
        ref.get.flatMap(orig => ref.set(project(orig)) *> fa.guarantee(ref.set(orig)))
    }
  }

def wireSync(
  priceHistoryStorage: PriceHistoryStorage[IO], // your implementation; the library ships none
  instrumentStorage: InstrumentStorage[IO],
  dividendStorage: DividendStorage[IO],
  watchlistStorage: WatchlistStorage[IO],
  alertHistoryStorage: AlertHistoryStorage[IO]
): IO[Unit] = {
  implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[YahooMarketDataSource[IO]])

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

  YahooMarketDataSource.clientResource[IO](yahooConfig).use { client =>
    val yahooApi = YahooApi.fromClient[IO](client)
    for {
      yahooSource <- YahooMarketDataSource.create[IO, IO](yahooApi)
      // Routes a ticker to whichever broker's watchlist entry carries it, falling back to Yahoo;
      // add further MarketDataSources (e.g. a Tinkoff one, for MOEX symbols) to the list as they
      // are wired.
      sources = new MarketDataSourcesImpl[IO](List(yahooSource), watchlistStorage, Source.Yahoo)
      // A sync failure is infrastructure-level, not per-symbol, so it is raised through the same
      // dispatcher/channels as any other alert; an empty channel list here still records it in
      // AlertHistoryStorage, just without delivery - see snippet (d) for a real Telegram channel,
      // which a real app shares with this one rather than building twice.
      dispatcher <- AlertDispatcherImpl.create[IO, IO](List.empty, alertHistoryStorage)
      systemAlerts <- SystemAlertsImpl.create[IO, IO](dispatcher, channels = List.empty)
      failureTracker <- FailureTrackerImpl.create[IO, IO]
      syncService <- PriceHistorySyncServiceImpl.create[IO, IO](
        sources,
        priceHistoryStorage,
        instrumentStorage,
        dividendStorage,
        syncConfig
      )
      implicit0(wl: WithLocal[IO, ProcessContext]) <- withLocalIO(ProcessContext("", AccountId(1)))
      implicit0(gu: GenUUID[IO]) = GenUUID.syncGenUUID[IO]
      syncProcess <- PriceHistorySyncProcessImpl
        .create[IO, IO](watchlistStorage, syncService, syncConfig, systemAlerts, failureTracker)
      _ <- syncProcess.run.compile.drain // normally run alongside your other processes via parJoin
    } yield ()
  }
}
```

`watchlistStorage.add(WatchlistItem(accountId, Ticker("AAPL"), Source.Yahoo, enabled = true, None,
now))` (e.g. from the `/watchlist add` command) is what puts a symbol in scope for the sync loop. A
sync failure raises a `sync-failed:<ticker>` system alert after 3 consecutive failures, and a
matching `sync-recovered:<ticker>` once it succeeds again.

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
import st.btu.trading.pillar.strategy.StrategyRegistryImpl
import tofu.logging.{Logging, Logs}

implicit val logs: Logs[IO, IO] =
  Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])

for {
  registry <- StrategyRegistryImpl.create[IO, IO]
  _        <- registry.register(new com.example.strategies.MaCross50x200)
} yield registry
```

### c) Run a backtest

Through `BacktestService` (loads bars from `PriceHistoryStorage` and persists the run):

```scala
import java.time.LocalDate
import st.btu.trading.pillar.models.market.{Source, Ticker}
import st.btu.trading.pillar.strategy.StrategyId
import st.btu.trading.pillar.strategy.backtest.{BacktestConfig, BacktestService, BacktestServiceImpl}
import st.btu.trading.pillar.storages.BacktestRunStorage
import tofu.logging.Logging

def runBacktest(
  registry: st.btu.trading.pillar.strategy.StrategyRegistry[IO],
  priceHistoryStorage: PriceHistoryStorage[IO],
  backtestRunStorage: BacktestRunStorage[IO]
): IO[Unit] = {
  implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[BacktestService[IO]])
  val config = BacktestConfig(initialCash = 100000, commissionRate = BigDecimal("0.0005"), slippageBps = 5)

  for {
    backtestService <- BacktestServiceImpl.create[IO, IO](registry, priceHistoryStorage, backtestRunStorage)
    run <- backtestService.run(
      StrategyId("ma-cross-50-200"),
      Ticker("AAPL"),
      Source.Yahoo,
      LocalDate.now.minusYears(2),
      LocalDate.now,
      config
    )
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
  sttpBackend: SttpBackend[IO, Any],
  strategyBindingStorage: StrategyBindingStorage[IO],
  alertRuleStorage: AlertRuleStorage[IO],
  alertHistoryStorage: AlertHistoryStorage[IO],
  priceHistoryStorage: PriceHistoryStorage[IO],
  paperStorage: PaperStorage[IO], // backs `/paper` and any paper-mode StrategyBinding
  marketDataSources: MarketDataSources[IO], // e.g. the `sources` router built in snippet (a)
  registry: st.btu.trading.pillar.strategy.StrategyRegistry[IO]
): IO[Unit] = {
  implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[AlertProcess[IO]])

  for {
    tgGateway    <- TgGatewayImpl.create[IO, IO](tg.Token("<bot-token>"), sttpBackend)
    channel      <- TelegramAlertChannel.create[IO, IO](tgGateway, tg.ChatId(123456789L))
    dispatcher   <- AlertDispatcherImpl.create[IO, IO](List(channel), alertHistoryStorage)
    systemAlerts <- SystemAlertsImpl.create[IO, IO](dispatcher, channels = List("telegram"))
    liveSvc      <- LiveStrategyServiceImpl.create[IO, IO](priceHistoryStorage, marketDataSources)
    paperBroker = PaperBrokerImpl.create[IO](
      paperStorage,
      PaperBrokerConfig(commissionRate = BigDecimal("0.0005"), slippageBps = 5)
    )
    implicit0(wl: WithLocal[IO, ProcessContext]) <- withLocalIO(ProcessContext("", AccountId(1)))
    implicit0(gu: GenUUID[IO]) = GenUUID.syncGenUUID[IO]
    process <- AlertProcessImpl.create[IO, IO](
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
    _ <- process.run.compile.drain
  } yield ()
}
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

[yfinance4s](https://github.com/coinductive/yfinance4s), the Yahoo Finance client, resolves from
Maven Central like every other dependency: `io.github.coinductive %% yfinance4s % 0.2.0`. No setup
step is needed before `sbt compile` or `sbt test`.

```bash
sbt test          # specs2 + cats-effect-testing; no network access required
sbt publishLocal   # publishes st.btu %% trading-pillar % 0.1.0-SNAPSHOT to ~/.ivy2/local
```

A consumer application then depends on that local artifact; run `sbt publishLocal` again after any
trading-pillar change the consumer needs to see.

## Status

- Tinkoff Invest, Binance and Telegram integrations: stable, in production use.
- Market data (Yahoo and Tinkoff, routed per ticker by `MarketDataSources`), strategy/backtest
  engine, and the alert pipeline: implemented and covered by the test suite. Yahoo Finance no
  longer serves MOEX (Russian exchange) data; MOEX symbols (e.g. `SBER`) are served instead through
  `TcsMarketDataSource` (Tinkoff Invest gRPC) - see [`docs/market-data.md`](docs/market-data.md).
- Analytics, dividend tracking, multi-symbol backtests, parameter sweeps and walk-forward
  validation, paper trading, a tax-estimate report and system alerts for sync/process failures are
  all implemented and covered by the test suite.
- The Binance gateway remains the one integration not yet wired to a sync process.
