package st.btu.trading.pillar.strategy.backtest

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Bar, Source, Ticker}
import st.btu.trading.pillar.strategy.{StrategyId, StrategyRegistryImpl, StrategySignal}
import st.btu.trading.pillar.testkit.StrategyFakes.ScriptedStrategy
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{MarketDataFakes, StrategyFakes}
import tofu.logging.{Logging, Logs}

import java.time.LocalDate

class BacktestServiceSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")
  private val from = LocalDate.of(2024, 1, 1)
  private val to = LocalDate.of(2024, 1, 3)
  private val config = BacktestConfig(10000, 0, 0)

  private def bar(date: LocalDate): Bar = StrategyFakes.flatBar(ticker, date, 100)

  private def bar(date: LocalDate, forTicker: Ticker): Bar =
    StrategyFakes.flatBar(forTicker, date, 100)

  private val knownStrategy = ScriptedStrategy(
    StrategyId("known"),
    warmupBars = 1,
    (_, _) => List(StrategySignal.Hold("hold"))
  )

  private def buildService(
    bars: List[Bar],
    runStorage: StrategyFakes.FakeBacktestRunStorage
  ): IO[BacktestService[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[BacktestService[IO]])
    for {
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
      registry <- StrategyRegistryImpl.create[IO, IO]
      _ <- registry.register(knownStrategy)
      service <- BacktestServiceImpl.create[IO, IO](registry, priceHistory, runStorage)
    } yield service
  }

  "BacktestServiceImpl" >> {
    "run" >> {

      "raises an error for an unregistered strategy id" >> {
        for {
          runStorage <- StrategyFakes.backtestRunStorage()
          service <- buildService(List(bar(from), bar(from.plusDays(1))), runStorage)
          result <- service
            .run(StrategyId("missing"), ticker, Source.Yahoo, from, to, config)
            .attempt
        } yield result must beLeft[Throwable].like { case e =>
          e must_== BacktestServiceError.StrategyNotFound(StrategyId("missing"))
        }
      }

      "runs the backtest and persists the result for a successful run" >> {
        val bars = List(bar(from), bar(from.plusDays(1)), bar(from.plusDays(2)))
        for {
          runStorage <- StrategyFakes.backtestRunStorage()
          service <- buildService(bars, runStorage)
          run <- service.run(knownStrategy.id, ticker, Source.Yahoo, from, to, config)
          saved <- runStorage.saved
        } yield {
          run.result.strategyId must_== knownStrategy.id
          run.result.ticker must_== ticker
          saved must haveSize(1)
          saved.head.id must_== run.id
          saved.head.result must_== run.result
        }
      }
    }

    "runPortfolio" >> {

      "raises an error for an unregistered strategy id" >> {
        for {
          runStorage <- StrategyFakes.backtestRunStorage()
          service <- buildService(List(bar(from), bar(from.plusDays(1))), runStorage)
          result <- service
            .runPortfolio(StrategyId("missing"), List(ticker), Source.Yahoo, from, to, config)
            .attempt
        } yield result must beLeft[Throwable].like { case e =>
          e must_== BacktestServiceError.StrategyNotFound(StrategyId("missing"))
        }
      }

      "runs the portfolio backtest and persists one combined run, with the tickers joined by comma as the symbol" >> {
        val tickerA = Ticker("AAA")
        val tickerB = Ticker("BBB")
        val bars =
          List(from, from.plusDays(1), from.plusDays(2)).flatMap(d =>
            List(bar(d, tickerA), bar(d, tickerB))
          )
        for {
          runStorage <- StrategyFakes.backtestRunStorage()
          service <- buildService(bars, runStorage)
          run <- service.runPortfolio(
            knownStrategy.id,
            List(tickerA, tickerB),
            Source.Yahoo,
            from,
            to,
            config
          )
          saved <- runStorage.saved
        } yield {
          run.result.strategyId must_== knownStrategy.id
          run.result.ticker must_== Ticker("AAA,BBB")
          saved must haveSize(1)
          saved.head.id must_== run.id
          saved.head.result must_== run.result
        }
      }
    }
  }
}
