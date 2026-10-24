package st.btu.trading.pillar.alerts

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.strategy.StrategySignal
import st.btu.trading.pillar.strategy.reference.MovingAverageCrossoverStrategy
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{AlertFakes, MarketDataFakes, StrategyFakes}
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

class LiveStrategyServiceImplSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")
  private val strategy = new MovingAverageCrossoverStrategy(fast = 2, slow = 3)
  private val binding = AlertFakes.strategyBinding(ticker = ticker, strategyId = strategy.id)
  private val now = ZonedDateTime.of(2024, 1, 4, 16, 0, 0, 0, ZoneOffset.UTC)

  private def bar(day: Int, close: BigDecimal): Bar =
    StrategyFakes.flatBar(ticker, LocalDate.of(2024, 1, day), close)

  private def buildService(bars: List[Bar]): IO[LiveStrategyService[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[LiveStrategyService[IO]])
    for {
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
      marketData <- MarketDataFakes.marketDataSource()
      sources <- MarketDataFakes.marketDataSources(yahoo =
        Some(MarketDataFakes.yahooSymbolsOver(marketData))
      )
      service <- LiveStrategyServiceImpl.create[IO, IO](priceHistory, sources)
    } yield service
  }

  "LiveStrategyServiceImpl" >> {
    "evaluate" >> {
      "emits a StrategyBuy alert once a new bar closes above the crossover, and does not repeat it for the same bar" >> {
        // warmupBars = slow + 1 = 4; fast(2)/slow(3) SMA crosses up exactly on the 4th (last) bar.
        val bars = List(bar(1, 10), bar(2, 10), bar(3, 10), bar(4, 13))
        for {
          service <- buildService(bars)
          first <- service.evaluate(binding, strategy, now)
          second <- service.evaluate(binding, strategy, now)
        } yield {
          first.alerts.map(_.kind) must_== List(AlertKind.StrategyBuy)
          second.alerts must beEmpty
          second.signals must beEmpty
        }
      }

      "carries the same bar's Buy/Sell signals independently of the alert" >> {
        val bars = List(bar(1, 10), bar(2, 10), bar(3, 10), bar(4, 13))
        for {
          service <- buildService(bars)
          evaluation <- service.evaluate(binding, strategy, now)
        } yield evaluation.signals must beLike {
          case List(StrategySignal.Buy(signalTicker, _, _)) =>
            signalTicker must_== ticker
        }
      }

      "emits nothing before the strategy has enough warmup history" >> {
        val bars = List(bar(2, 10), bar(3, 10), bar(4, 13))
        for {
          service <- buildService(bars)
          evaluation <- service.evaluate(binding, strategy, now)
        } yield {
          evaluation.alerts must beEmpty
          evaluation.signals must beEmpty
        }
      }
    }
  }
}
