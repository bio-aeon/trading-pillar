package st.btu.trading.pillar.gateways.binance

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{Clock, IO}
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import st.btu.trading.pillar.gateways.MarketDataSource
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.testkit.BinanceFakes.*
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO

import java.time.{Clock as _, *}

class BinanceMarketDataSourceSpec extends Specification with CatsEffect {

  private val bitcoin = Ticker("BTCUSDC")
  private val shiba = Ticker("SHIBUSDT")

  // 12:00 on 10 March 2026.
  private val now = at("2026-03-10T12:00:00Z")

  private val listed = List(pairOf("BTC", "USDC"), pairOf("SHIB", "USDT"))

  private val since = LocalDate.of(2026, 3, 1)
  private val until = LocalDate.of(2026, 3, 10)

  private final case class Fixture(
    source: MarketDataSource[IO],
    market: FakeBinanceMarket,
    clock: SteppingClock
  ) {
    def exchangeInfoReads: IO[Int] = market.calls.map(_.count(_ == MarketCall.ExchangeInfo))
  }

  private def fixture(script: MarketScript, start: Instant = now): IO[Fixture] =
    for {
      stepping <- steppingClock(start)
      market <- binanceMarket(script)
      source <- {
        implicit val clock: Clock[IO] = stepping.clock
        BinanceMarketDataSource.create[IO, IO](market)
      }
    } yield Fixture(source, market, stepping)

  // One closed day of a coin that costs about 1.5, and the day that is still going on.
  private val closedDay = dailyBar("2026-03-09", "1.4", "1.6", "1.3", "1.5", "10")
  private val currentDay = dailyBar("2026-03-10", "1.5", "1.7", "1.4", "1.6", "4")

  "BinanceMarketDataSource" >> {
    "dailyBars" >> {
      "gives the days that have ended, and leaves out the one still going on" >> {
        for {
          f <- fixture(MarketScript(bars = Map(symbol("BTCUSDC") -> List(closedDay, currentDay))))
          bars <- f.source.dailyBars(bitcoin, since, until)
        } yield bars.map(_.date) must_== List(LocalDate.of(2026, 3, 9))
      }

      "leaves out a bar whose close time is now, and gives it from the next millisecond" >> {
        val closesAt = currentDay.closeTime
        for {
          f <- fixture(
            MarketScript(bars = Map(symbol("BTCUSDC") -> List(currentDay))),
            start = closesAt
          )
          atClose <- f.source.dailyBars(bitcoin, since, until)
          _ <- f.clock.advance(Duration.ofMillis(1))
          afterClose <- f.source.dailyBars(bitcoin, since, until)
        } yield {
          atClose must beEmpty
          afterClose.map(_.date) must_== List(LocalDate.of(2026, 3, 10))
        }
      }

      "asks for the days from since to until" >> {
        for {
          f <- fixture(MarketScript(bars = Map(symbol("BTCUSDC") -> List(closedDay))))
          _ <- f.source.dailyBars(bitcoin, since, until)
          calls <- f.market.calls
        } yield calls must_== List(MarketCall.DailyBars(symbol("BTCUSDC"), since, until))
      }

      "keeps the digits of Binance's decimal strings, with the close as the adjusted close" >> {
        val cheap =
          dailyBar("2026-03-09", "0.00000821", "0.0000083", "0.00000815", "0.00000823", "9")
        for {
          f <- fixture(MarketScript(bars = Map(symbol("SHIBUSDT") -> List(cheap))))
          bars <- f.source.dailyBars(shiba, since, until)
        } yield bars.map(bar => (bar.open, bar.high, bar.low, bar.close, bar.adjClose)) must_==
          List(
            (
              BigDecimal("0.00000821"),
              BigDecimal("0.0000083"),
              BigDecimal("0.00000815"),
              BigDecimal("0.00000823"),
              BigDecimal("0.00000823")
            )
          )
      }

      "gives the base volume rounded down to whole units" >> {
        val volumes = List(
          dailyBar("2026-03-07", "1", "1", "1", "1", "1234567.99"),
          dailyBar("2026-03-08", "1", "1", "1", "1", "0.99"),
          dailyBar("2026-03-09", "1", "1", "1", "1", "5")
        )
        for {
          f <- fixture(MarketScript(bars = Map(symbol("BTCUSDC") -> volumes)))
          bars <- f.source.dailyBars(bitcoin, since, until)
        } yield bars.map(_.volume) must_== List(1234567L, 0L, 5L)
      }

      "names the ticker it was asked for and the Binance source on each bar" >> {
        for {
          f <- fixture(MarketScript(bars = Map(symbol("BTCUSDC") -> List(closedDay))))
          bars <- f.source.dailyBars(bitcoin, since, until)
        } yield bars.map(bar => (bar.ticker, bar.source)) must_== List((bitcoin, Source.Binance))
      }

      "fails as not found for a pair Binance does not know" >> {
        for {
          f <- fixture(MarketScript())
          failure <- f.source.dailyBars(bitcoin, since, until).attempt
        } yield failure must beLeft[Throwable](MarketDataError.NotFound(bitcoin))
      }
    }

    "latestQuote" >> {
      "is the price of the latest trade in the quote asset of the pair, as of when it was read" >> {
        for {
          f <- fixture(
            MarketScript(pairs = listed, prices = Map(symbol("BTCUSDC") -> BigDecimal("43251.01")))
          )
          quote <- f.source.latestQuote(bitcoin)
        } yield quote must beSome(
          Quote(
            bitcoin,
            BigDecimal("43251.01"),
            Currency("USDC"),
            ZonedDateTime.ofInstant(now, ZoneOffset.UTC),
            Source.Binance
          )
        )
      }

      "keeps the digits of a price below a cent" >> {
        for {
          f <- fixture(
            MarketScript(
              pairs = listed,
              prices = Map(symbol("SHIBUSDT") -> BigDecimal("0.00000823"))
            )
          )
          quote <- f.source.latestQuote(shiba)
        } yield quote.map(q => (q.price, q.currency)) must beSome(
          (BigDecimal("0.00000823"), Currency("USDT"))
        )
      }

      "gives nothing for a pair Binance does not list, without asking for its price" >> {
        for {
          f <- fixture(
            MarketScript(pairs = listed, prices = Map(symbol("ETHUSDC") -> BigDecimal(1)))
          )
          quote <- f.source.latestQuote(Ticker("ETHUSDC"))
          calls <- f.market.calls
        } yield {
          quote must beNone
          calls must_== List(MarketCall.ExchangeInfo)
        }
      }

      "gives nothing when Binance has no price for a pair it lists" >> {
        for {
          f <- fixture(MarketScript(pairs = listed))
          quote <- f.source.latestQuote(bitcoin)
        } yield quote must beNone
      }
    }

    "instrumentInfo" >> {
      "describes a pair that Binance lists" >> {
        for {
          f <- fixture(MarketScript(pairs = listed))
          info <- f.source.instrumentInfo(bitcoin)
        } yield info must beSome(
          InstrumentInfo(
            bitcoin,
            SourceSetting.Binance,
            Some("BTC/USDC"),
            "crypto",
            Currency("USDC"),
            "BINANCE",
            "UTC",
            None,
            ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
          )
        )
      }

      "gives nothing for a pair that Binance does not list" >> {
        for {
          f <- fixture(MarketScript(pairs = listed))
          info <- f.source.instrumentInfo(Ticker("ETHUSDC"))
        } yield info must beNone
      }
    }

    "the pairs Binance lists" >> {
      "are read when first needed, and shared by the quote and the description" >> {
        for {
          f <- fixture(
            MarketScript(pairs = listed, prices = Map(symbol("BTCUSDC") -> BigDecimal(1)))
          )
          before <- f.exchangeInfoReads
          _ <- f.source.latestQuote(bitcoin)
          _ <- f.source.instrumentInfo(shiba)
          after <- f.exchangeInfoReads
        } yield (before, after) must_== (0, 1)
      }

      "are not read again within the hour" >> {
        for {
          f <- fixture(MarketScript(pairs = listed))
          _ <- f.source.instrumentInfo(bitcoin)
          _ <- f.clock.advance(Duration.ofMinutes(59).plusSeconds(59))
          _ <- f.source.instrumentInfo(bitcoin)
          reads <- f.exchangeInfoReads
        } yield reads must_== 1
      }

      "are read again once an hour has passed" >> {
        for {
          f <- fixture(MarketScript(pairs = listed))
          _ <- f.source.instrumentInfo(bitcoin)
          _ <- f.clock.advance(Duration.ofHours(1))
          _ <- f.source.instrumentInfo(bitcoin)
          reads <- f.exchangeInfoReads
        } yield reads must_== 2
      }

      "are not read for the bars, which need no pair" >> {
        for {
          f <- fixture(MarketScript(bars = Map(symbol("BTCUSDC") -> List(closedDay))))
          _ <- f.source.dailyBars(bitcoin, since, until)
          reads <- f.exchangeInfoReads
        } yield reads must_== 0
      }
    }

    "failures" >> {
      val barsFail: BinanceError => MarketScript = error =>
        MarketScript(
          bars = Map(symbol("BTCUSDC") -> List(closedDay)),
          failures = { case MarketCall.DailyBars(_, _, _) => error }
        )

      "become the market data error that says what to do about them" >> {
        val tooManyRequests = BinanceError.RetriesExhausted(429, 5)
        val serverError = BinanceError.RetriesExhausted(503, 5)
        val unreachable = BinanceError.Unreachable("java.net.ConnectException: Connection refused")
        Fragments.foreach(
          List[(String, BinanceError, Throwable => Boolean)](
            (
              "429 tried again until the attempts ran out is a rate limit with no retry hint",
              tooManyRequests,
              _ == MarketDataError.RateLimited(None)
            ),
            (
              "a 5xx tried again until the attempts ran out is transient",
              serverError,
              {
                case MarketDataError.Transient(_, Some(cause)) => cause == serverError
                case _ => false
              }
            ),
            (
              "no answer is transient",
              unreachable,
              {
                case MarketDataError.Transient(_, Some(cause)) => cause == unreachable
                case _ => false
              }
            ),
            (
              "any other refusal is another failure",
              BinanceError.Refused(-1003, "Too many requests."),
              _.isInstanceOf[MarketDataError.Other]
            ),
            (
              "a ban is another failure",
              BinanceError.Banned("/api/v3/klines"),
              _.isInstanceOf[MarketDataError.Other]
            ),
            (
              "an unexpected answer is another failure",
              BinanceError.UnexpectedResponse("the answer is not JSON"),
              _.isInstanceOf[MarketDataError.Other]
            )
          )
        ) { case (description, error, isExpected) =>
          description >> {
            for {
              f <- fixture(barsFail(error))
              failure <- f.source.dailyBars(bitcoin, since, until).attempt
            } yield failure must beLeft[Throwable].like { case failed =>
              isExpected(failed) must beTrue
            }
          }
        }
      }

      "of the pairs are mapped too" >> {
        val script = MarketScript(
          pairs = listed,
          failures = { case MarketCall.ExchangeInfo => BinanceError.RetriesExhausted(429, 5) }
        )
        for {
          f <- fixture(script)
          failure <- f.source.instrumentInfo(bitcoin).attempt
        } yield failure must beLeft[Throwable](MarketDataError.RateLimited(None))
      }
    }
  }
}
