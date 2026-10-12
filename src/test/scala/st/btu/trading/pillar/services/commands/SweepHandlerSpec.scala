package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.{MarketDataSources, MarketDataSourcesImpl}
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.strategy.backtest.BacktestConfig
import st.btu.trading.pillar.strategy.reference.MovingAverageCrossoverStrategy
import st.btu.trading.pillar.strategy.{StrategyId, StrategyRegistry, StrategyRegistryImpl}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes, StrategyFakes}
import tofu.logging.{Logging, Logs}

import java.time.LocalDate

class SweepHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val defaults = BacktestConfig(10000, 0, 5)
  private val strategyId = StrategyId("ma-cross-2-3")
  private val ticker = Ticker("AAPL")
  private val today = LocalDate.now

  private def buildRegistry: IO[StrategyRegistry[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])
    StrategyRegistryImpl.create[IO, IO]
  }

  // No watchlist entries configured: forTicker always falls back to the single fake source's own
  // source (Yahoo, the same default StrategyFakes.flatBar uses).
  private def buildRouter: IO[MarketDataSources[IO]] =
    for {
      marketData <- MarketDataFakes.marketDataSource()
      watchlist <- MarketDataFakes.watchlistStorage()
    } yield new MarketDataSourcesImpl[IO](List(marketData), watchlist, marketData.source)

  // A fast(2)/slow(3) SMA crossover series (same shape as MovingAverageCrossoverStrategySpec's),
  // dated in the last 8 days so it falls inside the default 2-year lookback regardless of when
  // this suite runs.
  private val bars: List[Bar] = {
    val closes = List(10, 9, 8, 7, 6, 8, 11, 14).map(BigDecimal(_))
    closes.zipWithIndex.map { case (c, i) =>
      StrategyFakes.flatBar(ticker, today.minusDays((closes.size - i).toLong), c)
    }
  }

  "SweepHandler" >> {
    "handle" >> {

      "replies with a usage message when no grid axis token is given" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          sources <- buildRouter
          handler = new SweepHandler[IO](tgGateway, registry, priceHistory, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }

      "replies 'Unknown strategy' for an unregistered id" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          sources <- buildRouter
          handler = new SweepHandler[IO](tgGateway, registry, priceHistory, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", "fast=1:2:1"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain(s"Unknown strategy: ${strategyId.value}")
      }

      "replies with a short message when the registered strategy doesn't support parameters" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(StrategyFakes.holdStrategy(strategyId))
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          sources <- buildRouter
          handler = new SweepHandler[IO](tgGateway, registry, priceHistory, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", "fast=1:2:1"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("does not support parameters")
      }

      "replies with the grid error when the cartesian product exceeds the 100-combination cap" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 2, slow = 3))
          priceHistory <- MarketDataFakes.priceHistoryStorage()
          sources <- buildRouter
          handler = new SweepHandler[IO](tgGateway, registry, priceHistory, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", "fast=1:20:1", "slow=21:30:1"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Invalid grid")
      }

      "reports the grid size and the top points by Sharpe, each with params, return, Sharpe, drawdown and trades" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 2, slow = 3))
          priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
          sources <- buildRouter
          handler = new SweepHandler[IO](tgGateway, registry, priceHistory, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", "fast=1:2:1", "slow=3:3:1"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Grid: 2 combination(s)")
          msg must contain("fast=1, slow=3")
          msg must contain("fast=2, slow=3")
          msg must contain("return")
          msg must contain("Sharpe")
          msg must contain("max drawdown")
          msg must contain("trades")
        }
      }

      "replies with 'No successful sweep points' when every point in the grid fails" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(new MovingAverageCrossoverStrategy(fast = 2, slow = 3))
          priceHistory <- MarketDataFakes.priceHistoryStorage() // no bars stored anywhere
          sources <- buildRouter
          handler = new SweepHandler[IO](tgGateway, registry, priceHistory, sources, defaults)
          _ <- handler.handle(chatId, List(strategyId.value, "AAPL", "fast=1:2:1"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Grid: 2 combination(s)")
          msg must contain("No successful sweep points.")
        }
      }
    }
  }
}
