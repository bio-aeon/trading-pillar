package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.MarketDataSources
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.strategy.StrategyId.value
import st.btu.trading.pillar.strategy.backtest.*
import st.btu.trading.pillar.strategy.reference.MovingAverageCrossoverStrategy
import st.btu.trading.pillar.strategy.{
  BacktestRunId,
  StrategyId,
  StrategyRegistry,
  StrategyRegistryImpl
}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes, StrategyFakes}
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZonedDateTime}

class BacktestHandlerSpec extends Specification with CatsEffect {

  implicit val logging: Logging[IO] = Logging.Make.plain[IO].forService[BacktestHandler[IO]]

  private val chatId = ChatId(1L)
  private val defaults = BacktestConfig(10000, BigDecimal("0.001"), 5)
  private val strategyId = StrategyId("ma-cross-50-200")
  private val ticker = Ticker("AAPL")
  private val tenFortyParams = "params=fast:10,slow:40"

  private def buildRouter: IO[MarketDataSources[IO]] = MarketDataFakes.marketDataSources()

  private def buildRegistry: IO[StrategyRegistry[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])
    StrategyRegistryImpl.create[IO, IO]
  }

  private def metrics(
    total: BigDecimal = BigDecimal("0.1234"),
    annualized: BigDecimal = BigDecimal("0.2"),
    drawdown: BigDecimal = BigDecimal("0.05"),
    sharpe: BigDecimal = BigDecimal("1.5"),
    winRate: Option[BigDecimal] = Some(BigDecimal("0.6")),
    buyAndHold: BigDecimal = BigDecimal("0.08")
  ): BacktestMetrics =
    BacktestMetrics(total, annualized, drawdown, sharpe, winRate, 4, 2, 11234, buyAndHold)

  // trades is deliberately left empty: the reply must read the trade count from
  // metrics.tradeCount, not result.trades.size.
  private def run(
    id: Long,
    m: BacktestMetrics,
    bars: Int,
    runStrategyId: StrategyId = strategyId,
    parameters: Map[String, String] = Map("fast" -> "50", "slow" -> "200")
  ): BacktestRun = {
    val result = BacktestResult(
      runStrategyId,
      ticker,
      parameters,
      LocalDate.of(2024, 1, 1),
      LocalDate.of(2026, 1, 1),
      defaults,
      Nil,
      List.fill(bars)(EquityPoint(LocalDate.of(2024, 1, 1), 10000, 10000, 0)),
      m,
      Nil
    )
    BacktestRun(BacktestRunId(id), ZonedDateTime.now, result)
  }

  "BacktestHandler" >> {
    "handle" >> {

      "replies with a usage message when arguments are missing" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(new RuntimeException("unused"))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }

      "formats a successful run with run id, period, bar count, parameters and metrics as percentages" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ => IO.pure(run(7L, metrics(), 400)))
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl", "2"))
          messages <- tgGateway.sentMessages
          calls <- service.calls
        } yield {
          val msg = messages.head.message
          calls must_== List(strategyId)
          msg must contain("Backtest #7")
          msg must contain("Bars: 400")
          msg must contain("Parameters: fast=50, slow=200")
          msg must contain("Trades: 4")
          msg must contain("Total return: 12.34%")
          msg must contain("Annualized: 20.00%")
          msg must contain("Max drawdown: 5.00%")
          msg must contain("Sharpe: 1.50")
          msg must contain("Win rate: 60.00%")
          msg must contain("Buy and hold: 8.00%")
        }
      }

      "reports n/a for win rate when there were no round trips" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ =>
            IO.pure(run(1L, metrics(winRate = None), 10))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Win rate: n/a")
      }

      "explains that the strategy wasn't found" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(id =>
            IO.raiseError(BacktestServiceError.StrategyNotFound(id))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain(strategyId.value)
      }

      "suggests /watchlist add when there aren't enough bars" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(BacktestServiceError.Failed(BacktestError.NotEnoughBars(200, 50)))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("need 200 bars, have 50")
          msg must contain("/watchlist add AAPL")
        }
      }

      "lets any other backtest failure propagate to the caller" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(new RuntimeException("storage exploded"))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          result <- handler.handle(chatId, List(strategyId.value, "AAPL")).attempt
        } yield result must beLeft[Throwable].like { case e =>
          e.getMessage must_== "storage exploded"
        }
      }
    }

    "params=" >> {

      "runs an ad hoc strategy built from the given parameters and reports the parameters used" >> {
        val adHocId = StrategyId("ma-cross-10-40")
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 50, slow = 200))
          service <- StrategyFakes.backtestService(id =>
            IO.pure(run(9L, metrics(), 300, id, Map("fast" -> "10", "slow" -> "40")))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl", tenFortyParams))
          messages <- tgGateway.sentMessages
          calls <- service.calls
        } yield {
          val msg = messages.head.message
          calls must_== List(adHocId)
          msg must contain("Backtest #9")
          msg must contain(adHocId.value)
          msg must contain("Parameters: fast=10, slow=40")
        }
      }

      "accepts a years token positioned before the params= token" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 50, slow = 200))
          service <- StrategyFakes.backtestService(id => IO.pure(run(2L, metrics(), 10, id)))
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl", "3", tenFortyParams))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Backtest #2")
      }

      "replies 'Unknown strategy' when the looked-up strategy isn't registered" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(new RuntimeException("unused"))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", tenFortyParams))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain(s"Unknown strategy: ${strategyId.value}")
      }

      "replies with a short message when the registered strategy doesn't support parameters" >> {
        val plain = StrategyFakes.holdStrategy(strategyId)
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(plain)
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(new RuntimeException("unused"))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", tenFortyParams))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("does not support parameters")
      }

      "replies with the parameter-validation failure message for an unknown parameter key" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 50, slow = 200))
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(new RuntimeException("unused"))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", "params=bogus:1"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Invalid parameters")
          msg must contain("bogus")
        }
      }

      "replies with usage when the params token is malformed" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 50, slow = 200))
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(new RuntimeException("unused"))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", "params=fast-10"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }
    }

    "equity-curve image" >> {

      "sends the equity-curve image after a successful run with at least 2 equity points" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ => IO.pure(run(7L, metrics(), 400)))
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl", "2"))
          photos <- tgGateway.sentPhotos
        } yield {
          photos must haveSize(1)
          photos.head.chatId must_== chatId
          photos.head.filename must_== "backtest-7-equity.png"
          photos.head.content.length must be_>(0)
          photos.head.caption must beNone
        }
      }

      "does not send an image when the run has fewer than 2 equity points" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ => IO.pure(run(3L, metrics(), 1)))
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl"))
          photos <- tgGateway.sentPhotos
        } yield photos must beEmpty
      }
    }

    "stored bars" >> {

      // No adapter is wired for any source: the handler may consult only the tickers' settings.
      def routerWith(settings: List[TickerSource]): IO[MarketDataSources[IO]] =
        MarketDataFakes.marketDataSources(settings = settings)

      val sber = Ticker("SBER")
      val sberOnTinkoff = List(TickerSource(sber, SourceSetting.Tinkoff(None)))

      "reads a ticker set to Tinkoff from its Tinkoff bars, with no Tinkoff adapter wired" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ => IO.pure(run(1L, metrics(), 10)))
          sources <- routerWith(sberOnTinkoff)
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "sber"))
          messages <- tgGateway.sentMessages
          readSources <- service.readSources
        } yield {
          readSources must_== List(Source.Tinkoff)
          messages.head.message must contain("Backtest #1")
        }
      }

      "reads a ticker with no setting from the default source" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ => IO.pure(run(1L, metrics(), 10)))
          sources <- routerWith(Nil)
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl"))
          readSources <- service.readSources
        } yield readSources must_== List(Source.Yahoo)
      }

      "reads the bars of a params= run from the source the ticker is set to" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 50, slow = 200))
          service <- StrategyFakes.backtestService(_ => IO.pure(run(2L, metrics(), 10)))
          sources <- routerWith(sberOnTinkoff)
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "sber", tenFortyParams))
          readSources <- service.readSources
        } yield readSources must_== List(Source.Tinkoff)
      }

      "reads each ticker of a portfolio from the source it is set to" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ => IO.pure(run(3L, metrics(), 10)))
          sources <- routerWith(sberOnTinkoff)
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl,sber"))
          portfolios <- service.portfolioTickers
        } yield portfolios must_== List(
          List(SourcedTicker(ticker, Source.Yahoo), SourcedTicker(sber, Source.Tinkoff))
        )
      }
    }

    "multi-ticker" >> {

      "routes a comma-separated ticker argument to a portfolio backtest run" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ => IO.pure(run(11L, metrics(), 400)))
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl,msft", "2"))
          messages <- tgGateway.sentMessages
          calls <- service.calls
        } yield {
          calls must_== List(strategyId)
          messages.head.message must contain("Backtest #11")
        }
      }

      "rejects params= combined with multiple tickers" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          service <- StrategyFakes.backtestService(_ =>
            IO.raiseError(new RuntimeException("unused"))
          )
          sources <- buildRouter
          handler = new BacktestHandler[IO](tgGateway, registry, service, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "aapl,msft", tenFortyParams))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("not supported with multiple tickers")
      }
    }
  }
}
