package st.btu.trading.pillar.alerts

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.*
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

  private def quoteAt(asOf: ZonedDateTime): Quote =
    Quote(ticker, BigDecimal(200), Currency("USD"), asOf, Source.Yahoo)

  private def listedIn(timezone: String): InstrumentInfo =
    InstrumentInfo(
      ticker,
      SourceSetting.Yahoo(None),
      None,
      "EQUITY",
      Currency("USD"),
      "NMS",
      timezone,
      None,
      now
    )

  private def buildService(
    bars: List[Bar],
    quote: Option[Quote] = None,
    timezone: String = "America/New_York"
  ): IO[LiveStrategyService[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[LiveStrategyService[IO]])
    for {
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
      marketData <- MarketDataFakes.marketDataSource(
        quotes = Map(ticker -> IO.pure(quote)),
        info = Map(ticker -> IO.pure(Some(listedIn(timezone))))
      )
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

      "approaching signal" >> {
        // Thursday's history ends in SMA(2) 200 below SMA(3) 202; a quote of 200 for Friday brings
        // them to 199.5 and 200, so a buy approaches, 0.25% away. The stored bars hold no
        // crossover, so the warning is the only alert.
        val throughThursday = List(bar(1, 210), bar(2, 206), bar(3, 201), bar(4, 199))
        val fridayClose = ZonedDateTime.of(2024, 1, 5, 21, 0, 0, 0, ZoneOffset.UTC)
        val weekend = ZonedDateTime.of(2024, 1, 6, 12, 0, 0, 0, ZoneOffset.UTC)
        val buyApproaching = List((AlertKind.StrategyPreBuy, Some(BigDecimal("0.0025"))))

        def warnings(evaluation: LiveEvaluation): List[(AlertKind, Option[BigDecimal])] =
          evaluation.alerts.map(alert => (alert.kind, alert.triggerValue))

        "judges the quote as the bar of a session not stored yet" >> {
          for {
            service <- buildService(throughThursday, Some(quoteAt(fridayClose)))
            evaluation <- service.evaluate(binding, strategy, weekend)
          } yield warnings(evaluation) must_== buyApproaching
        }

        "counts the quote's session once when its bar is already stored" >> {
          for {
            service <- buildService(throughThursday :+ bar(5, 200), Some(quoteAt(fridayClose)))
            evaluation <- service.evaluate(binding, strategy, weekend)
          } yield warnings(evaluation) must_== buyApproaching
        }

        "dates the quote's session in the exchange's time zone" >> {
          // Still Thursday in UTC, but Friday morning in Auckland.
          val aucklandFriday = ZonedDateTime.of(2024, 1, 4, 22, 0, 0, 0, ZoneOffset.UTC)
          for {
            service <- buildService(
              throughThursday,
              Some(quoteAt(aucklandFriday)),
              "Pacific/Auckland"
            )
            evaluation <- service.evaluate(binding, strategy, weekend)
          } yield warnings(evaluation) must_== buyApproaching
        }
      }
    }
  }
}
