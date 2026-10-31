package st.btu.trading.pillar.alerts

import cats.effect.{IO, Ref}
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.PriceHistoryStorage
import st.btu.trading.pillar.strategy.*
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

  // A caller whose dispatch fails, so that it never acts on the evaluation.
  private val failingAction: LiveEvaluation => IO[Unit] =
    _ => IO.raiseError(new RuntimeException("dispatch failed"))

  private def bar(day: Int, close: BigDecimal): Bar =
    StrategyFakes.flatBar(ticker, LocalDate.of(2024, 1, day), close)

  private def quoteAt(asOf: ZonedDateTime, price: BigDecimal = BigDecimal(200)): Quote =
    Quote(ticker, price, Currency("USD"), asOf, Source.Yahoo)

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
  ): IO[LiveStrategyService[IO]] =
    MarketDataFakes.priceHistoryStorage(bars).flatMap(serviceOver(_, IO.pure(quote), timezone))

  private def serviceOver(
    priceHistory: PriceHistoryStorage[IO],
    latestQuote: IO[Option[Quote]],
    timezone: String = "America/New_York"
  ): IO[LiveStrategyService[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[LiveStrategyService[IO]])
    for {
      marketData <- MarketDataFakes.marketDataSource(
        quotes = Map(ticker -> latestQuote),
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
          first <- service.evaluate(binding, strategy, now)(IO.pure)
          second <- service.evaluate(binding, strategy, now)(IO.pure)
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
          evaluation <- service.evaluate(binding, strategy, now)(IO.pure)
        } yield evaluation.signals must beLike {
          case List(StrategySignal.Buy(signalTicker, _, _)) =>
            signalTicker must_== ticker
        }
      }

      "emits nothing before the strategy has enough warmup history" >> {
        val bars = List(bar(2, 10), bar(3, 10), bar(4, 13))
        for {
          service <- buildService(bars)
          evaluation <- service.evaluate(binding, strategy, now)(IO.pure)
        } yield {
          evaluation.alerts must beEmpty
          evaluation.signals must beEmpty
        }
      }

      "after a failed poll" >> {
        // fast(2)/slow(3) SMA crosses up on the 4th bar, so a buy is due.
        val crossover = List(bar(1, 10), bar(2, 10), bar(3, 10), bar(4, 13))

        "signals the same bar again when acting on it failed" >> {
          for {
            service <- buildService(crossover)
            failed <- service.evaluate(binding, strategy, now)(failingAction).attempt
            retried <- service.evaluate(binding, strategy, now)(IO.pure)
          } yield {
            failed must beLeft
            retried.signals must beLike { case List(StrategySignal.Buy(_, _, _)) => ok }
          }
        }

        "signals the same bar again when its quote could not be read" >> {
          for {
            reads <- Ref.of[IO, Int](0)
            quoteFailingOnce = reads.getAndUpdate(_ + 1).flatMap { n =>
              if (n == 0) IO.raiseError[Option[Quote]](MarketDataError.RateLimited(None))
              else IO.pure(Option.empty[Quote])
            }
            history <- MarketDataFakes.priceHistoryStorage(crossover)
            service <- serviceOver(history, quoteFailingOnce)
            failed <- service.evaluate(binding, strategy, now)(IO.pure).attempt
            retried <- service.evaluate(binding, strategy, now)(IO.pure)
          } yield {
            failed must beLeft
            retried.signals must beLike { case List(StrategySignal.Buy(_, _, _)) => ok }
          }
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
            evaluation <- service.evaluate(binding, strategy, weekend)(IO.pure)
          } yield warnings(evaluation) must_== buyApproaching
        }

        "counts the quote's session once when its bar is already stored" >> {
          for {
            service <- buildService(throughThursday :+ bar(5, 200), Some(quoteAt(fridayClose)))
            evaluation <- service.evaluate(binding, strategy, weekend)(IO.pure)
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
            evaluation <- service.evaluate(binding, strategy, weekend)(IO.pure)
          } yield warnings(evaluation) must_== buyApproaching
        }
      }

      "warning cadence" >> {
        // Warns at a distance equal to the quote's price: of a buy while flat, of a sell while
        // holding. It buys on Monday's bar.
        val monday = LocalDate.of(2024, 1, 8)
        val warner = StrategyFakes.ScriptedStrategy(
          StrategyId("warner"),
          warmupBars = 1,
          onBarFn = (_, bar) =>
            if (bar.date == monday) List(StrategySignal.Buy(ticker, Sizing.AllIn, "buy"))
            else List(StrategySignal.Hold("hold")),
          proximityFn = (ctx, bar) =>
            Some(PreSignal(if (ctx.position.isEmpty) Side.Buy else Side.Sell, bar.close, "near"))
        )
        val friday = bar(5, 100)
        val preBuy = List(AlertKind.StrategyPreBuy)

        def utc(day: Int, hour: Int): ZonedDateTime =
          ZonedDateTime.of(2024, 1, day, hour, 0, 0, 0, ZoneOffset.UTC)

        // Each poll quotes the distance to warn at as of `asOf`, and returns the kinds of the
        // alerts raised.
        def poller(
          priceHistory: PriceHistoryStorage[IO]
        ): IO[(ZonedDateTime, String) => IO[List[AlertKind]]] =
          for {
            quote <- Ref.of[IO, Option[Quote]](None)
            service <- serviceOver(priceHistory, quote.get)
          } yield (asOf, distance) =>
            quote.set(Some(quoteAt(asOf, BigDecimal(distance)))) >>
              service.evaluate(binding, warner, asOf)(IO.pure).map(_.alerts.map(_.kind))

        "warns once per session while the warning stands" >> {
          for {
            history <- MarketDataFakes.priceHistoryStorage(List(friday))
            poll <- poller(history)
            morning <- poll(utc(8, 15), "0.004")
            noon <- poll(utc(8, 17), "0.003")
          } yield List(morning, noon) must_== List(preBuy, Nil)
        }

        "warns again within the session once the distance has halved" >> {
          for {
            history <- MarketDataFakes.priceHistoryStorage(List(friday))
            poll <- poller(history)
            first <- poll(utc(8, 15), "0.004")
            nearlyHalved <- poll(utc(8, 16), "0.0021")
            halved <- poll(utc(8, 17), "0.002")
          } yield List(first, nearlyHalved, halved) must_== List(preBuy, Nil, preBuy)
        }

        "warns once while the distance stays at 0" >> {
          for {
            history <- MarketDataFakes.priceHistoryStorage(List(friday))
            poll <- poller(history)
            first <- poll(utc(8, 15), "0")
            again <- poll(utc(8, 16), "0")
          } yield List(first, again) must_== List(preBuy, Nil)
        }

        "stays silent after the close once the session's bar is stored" >> {
          for {
            history <- MarketDataFakes.priceHistoryStorage(List(friday))
            poll <- poller(history)
            beforeClose <- poll(utc(9, 15), "0.004")
            _ <- history.upsertBars(List(bar(9, 100)))
            afterClose <- poll(utc(9, 22), "0.004")
          } yield List(beforeClose, afterClose) must_== List(preBuy, Nil)
        }

        "warns again on the next session" >> {
          for {
            history <- MarketDataFakes.priceHistoryStorage(List(friday))
            poll <- poller(history)
            onMonday <- poll(utc(8, 15), "0.004")
            onTuesday <- poll(utc(9, 15), "0.004")
          } yield List(onMonday, onTuesday) must_== List(preBuy, preBuy)
        }

        "warns again when acting on the warning failed" >> {
          val mondayMorning = utc(8, 15)
          val quote = IO.pure(Some(quoteAt(mondayMorning, BigDecimal("0.004"))))
          for {
            history <- MarketDataFakes.priceHistoryStorage(List(friday))
            service <- serviceOver(history, quote)
            _ <- service.evaluate(binding, warner, mondayMorning)(failingAction).attempt
            retried <- service.evaluate(binding, warner, mondayMorning.plusMinutes(5))(IO.pure)
          } yield retried.alerts.map(_.kind) must_== preBuy
        }

        "warns of the other side within the session once the position has turned" >> {
          for {
            history <- MarketDataFakes.priceHistoryStorage(List(friday))
            poll <- poller(history)
            beforeClose <- poll(utc(8, 15), "0.004")
            _ <- history.upsertBars(List(bar(8, 100)))
            afterClose <- poll(utc(8, 22), "0.004")
          } yield List(beforeClose, afterClose) must_==
            List(preBuy, List(AlertKind.StrategyBuy, AlertKind.StrategyPreSell))
        }
      }
    }
  }
}
