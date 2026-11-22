package st.btu.trading.pillar.gateways.binance

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.syntax.parallel.*
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.security.{Bouncy, CryptoImpl}
import st.btu.trading.pillar.testkit.BinanceFakes.{at, pairOf, symbol}
import st.btu.trading.pillar.testkit.BinanceHttpFakes.*
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.VirtualTime
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZoneOffset}
import scala.concurrent.duration.*

class BinanceMarketImplSpec extends Specification with CatsEffect {

  private implicit val bouncy: Bouncy = new Bouncy {}

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[BinanceMarket[IO]])

  // Calls that wait next to nothing for their turn, and for a retry.
  private val fast = BinanceApiConfig(
    limitWindow = 1.milli,
    retryDelay = 1.milli,
    rateLimitDelay = 1.milli,
    maxAttempts = 3
  )

  private final case class Fixture(market: BinanceMarket[IO], calls: IO[List[Call]]) {
    def callsTo(path: String): IO[List[Call]] = calls.map(_.filter(_.path == path))
  }

  private def fixture(
    config: BinanceApiConfig = fast,
    respond: Call => Reply = _ => answer("[]")
  ): IO[Fixture] =
    for {
      http <- server(respond = respond)
      ipBuckets <- BinanceIpBuckets.create[IO, IO]
      market <- BinanceMarketImpl.create[IO, IO](config, http.backend, ipBuckets)
    } yield Fixture(market, http.calls)

  private val ExchangeInfo = "/api/v3/exchangeInfo"
  private val Klines = "/api/v3/klines"
  private val TickerPrice = "/api/v3/ticker/price"
  private val btcusdc = symbol("BTCUSDC")

  private val firstDay = LocalDate.of(2026, 3, 9)
  private val lastDay = LocalDate.of(2026, 3, 10)

  private def epoch(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli

  // A bar of `klines` that opens on `day` and has `close` as its close.
  private def kline(day: LocalDate, close: String = "1.5"): String =
    s"""[${epoch(day)},"1","2","0.5","$close","10",${epoch(day) + 86399999},"15",100,"4","6","0"]"""

  // Answers as Binance does: one bar for each day from `first` to `last` that opens within the
  // times asked for, at most as many as the limit.
  private def barsBetween(first: LocalDate, last: LocalDate)(call: Call): Reply = {
    val start = call.params("startTime").toLong
    val end = call.params("endTime").toLong
    val days = Iterator
      .iterate(first)(_.plusDays(1))
      .takeWhile(!_.isAfter(last))
      .filter(day => epoch(day) >= start && epoch(day) <= end)
      .take(call.params("limit").toInt)
    answer(days.map(kline(_)).mkString("[", ",", "]"))
  }

  "BinanceMarketImpl" >> {
    "calls" >> {
      "carry neither a key nor a signature, and do not read the offset to Binance's clock" >> {
        for {
          f <- fixture(respond =
            call =>
              call.path match {
                case ExchangeInfo => answer("""{"symbols":[]}""")
                case TickerPrice => answer("""{"symbol":"BTCUSDC","price":"1"}""")
                case _ => answer("[]")
              }
          )
          _ <- f.market.exchangeInfo
          _ <- f.market.minuteClose(btcusdc, at("2026-03-10T12:34:00Z"))
          _ <- f.market.dailyBars(btcusdc, firstDay, lastDay)
          _ <- f.market.lastPrice(btcusdc)
          calls <- f.calls
        } yield {
          calls.map(_.path) must_== List(ExchangeInfo, Klines, Klines, TickerPrice)
          calls.map(_.key).distinct must_== List(None)
          calls.exists(_.query.contains("signature")) must beFalse
          calls.map(_.method).distinct must_== List("GET")
        }
      }

      "go to the API host for the pairs and to the market data host for the bars and the price" >> {
        for {
          f <- fixture(respond =
            call =>
              if (call.path == ExchangeInfo) answer("""{"symbols":[]}""")
              else if (call.path == TickerPrice)
                answer("""{"symbol":"BTCUSDC","price":"1"}""")
              else answer("[]")
          )
          _ <- f.market.exchangeInfo
          _ <- f.market.dailyBars(btcusdc, firstDay, firstDay)
          _ <- f.market.lastPrice(btcusdc)
          calls <- f.calls
        } yield calls.map(_.host) must_== List(ApiHost, MarketHost, MarketHost)
      }
    }

    "exchangeInfo" >> {
      "gives each pair with its assets" >> {
        val body =
          """{"symbols":[{"symbol":"BTCUSDC","status":"TRADING","baseAsset":"BTC","quoteAsset":"USDC"},
            |{"symbol":"ETHBTC","status":"BREAK","baseAsset":"ETH","quoteAsset":"BTC"}]}""".stripMargin
        for {
          f <- fixture(respond = _ => answer(body))
          pairs <- f.market.exchangeInfo
        } yield pairs must_== List(pairOf("BTC", "USDC"), pairOf("ETH", "BTC"))
      }
    }

    "dailyBars" >> {
      "asks for the days from the start of the first to the end of the last, in a page of 1,000" >> {
        for {
          f <- fixture(respond = barsBetween(LocalDate.of(2026, 3, 1), lastDay))
          _ <- f.market.dailyBars(btcusdc, firstDay, lastDay)
          calls <- f.callsTo(Klines)
        } yield calls.map(
          _.params.view.filterKeys(Set("symbol", "interval", "limit")).toMap
        ) must_==
          List(Map("symbol" -> "BTCUSDC", "interval" -> "1d", "limit" -> "1000")) and
          (calls.map(_.params("startTime")) must_== List(epoch(firstDay).toString)) and
          (calls.map(_.params("endTime")) must_== List(
            at("2026-03-10T23:59:59.999Z").toEpochMilli.toString
          ))
      }

      "gives each bar its UTC date, and its prices, volume and close time as Binance wrote them" >> {
        val body =
          """[[1773014400000,"0.00000821","0.0000083","0.00000815","0.00000823","1234567.5",
            |1773100799999,"10.2",100,"600","5","0"]]""".stripMargin
        for {
          f <- fixture(respond = _ => answer(body))
          bars <- f.market.dailyBars(btcusdc, firstDay, firstDay)
        } yield bars must_== List(
          BinanceDailyBar(
            firstDay,
            BigDecimal("0.00000821"),
            BigDecimal("0.0000083"),
            BigDecimal("0.00000815"),
            BigDecimal("0.00000823"),
            BigDecimal("1234567.5"),
            at("2026-03-09T23:59:59.999Z")
          )
        )
      }

      "reads on from the day after the last bar of a full page, up to a short page" >> {
        // From 1 January 2022 to 10 March 2026 are 1,530 days. The first 1,000 of them end on 26
        // September 2024, so the second page starts on the 27th.
        val first = LocalDate.of(2022, 1, 1)
        val last = lastDay
        for {
          f <- fixture(respond = barsBetween(first, last))
          bars <- f.market.dailyBars(btcusdc, first, last)
          calls <- f.callsTo(Klines)
        } yield {
          calls.map(_.params("startTime")) must_==
            List(epoch(first), epoch(LocalDate.of(2024, 9, 27))).map(_.toString)
          bars.size must_== 1530
          bars.map(_.date).head must_== first
          bars.map(_.date).last must_== last
          val dates = bars.map(_.date)
          dates.zip(dates.drop(1)).forall { case (day, next) =>
            next == day.plusDays(1)
          } must beTrue
        }
      }

      "does not ask again after a full page that reaches the last day" >> {
        val first = LocalDate.of(2022, 1, 1)
        val last = LocalDate.of(2024, 9, 26)
        for {
          f <- fixture(respond = barsBetween(first, last))
          bars <- f.market.dailyBars(btcusdc, first, last)
          calls <- f.callsTo(Klines)
        } yield {
          bars.size must_== 1000
          calls.size must_== 1
        }
      }

      "asks again after a full page that leaves days to go, and stops at a page with no bar" >> {
        // The pair has bars up to 26 September 2024 only, as one that stopped trading has.
        val first = LocalDate.of(2022, 1, 1)
        for {
          f <- fixture(respond = barsBetween(first, LocalDate.of(2024, 9, 26)))
          bars <- f.market.dailyBars(btcusdc, first, lastDay)
          calls <- f.callsTo(Klines)
        } yield {
          bars.size must_== 1000
          calls.size must_== 2
        }
      }

      "stops when an answer brings no day past where it started" >> {
        val first = LocalDate.of(2022, 1, 1)
        val page = (0 until 1000).map(i => kline(first.plusDays(i.toLong))).mkString("[", ",", "]")
        for {
          f <- fixture(respond = _ => answer(page))
          _ <- f.market.dailyBars(btcusdc, first, lastDay)
          calls <- f.callsTo(Klines)
        } yield calls.size must_== 2
      }

      "gives no bars, and asks for none, when the first day is after the last" >> {
        for {
          f <- fixture()
          bars <- f.market.dailyBars(btcusdc, lastDay, firstDay)
          calls <- f.calls
        } yield {
          bars must beEmpty
          calls must beEmpty
        }
      }

      "gives no bars for days before the pair was listed" >> {
        for {
          f <- fixture(respond = _ => answer("[]"))
          bars <- f.market.dailyBars(btcusdc, LocalDate.of(2010, 1, 1), LocalDate.of(2010, 1, 31))
        } yield bars must beEmpty
      }

      "fails with Binance's refusal for a pair it does not know" >> {
        for {
          f <- fixture(respond = _ => refusal(-1121, "Invalid symbol."))
          failure <- f.market
            .dailyBars(symbol("NOPE"), firstDay, lastDay)
            .attempt
        } yield failure must beLeft[Throwable](BinanceError.Refused(-1121, "Invalid symbol."))
      }

      "fails when a bar lacks a value" >> {
        for {
          f <- fixture(respond = _ => answer("""[[1773014400000,"1","2","0.5"]]"""))
          failure <- f.market
            .dailyBars(btcusdc, firstDay, firstDay)
            .attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "lastPrice" >> {
      "gives the price of the latest trade of the pair" >> {
        for {
          f <- fixture(respond = _ => answer("""{"symbol":"BTCUSDC","price":"43251.01000000"}"""))
          price <- f.market.lastPrice(btcusdc)
          calls <- f.callsTo(TickerPrice)
        } yield {
          price must beSome(BigDecimal("43251.01"))
          calls.map(_.params("symbol")) must_== List("BTCUSDC")
        }
      }

      "gives nothing for a pair Binance does not know" >> {
        for {
          f <- fixture(respond = _ => refusal(-1121, "Invalid symbol."))
          price <- f.market.lastPrice(symbol("NOPE"))
        } yield price must beNone
      }

      "keeps any other refusal" >> {
        for {
          f <- fixture(respond = _ => refusal(-1003, "Too many requests."))
          failure <- f.market.lastPrice(btcusdc).attempt
        } yield failure must beLeft[Throwable].like { case BinanceError.Refused(-1003, _) => ok }
      }
    }

    "pacing" >> {
      // A call of weight 20 on /api/v3 has 15 s * 20 / (6000 * 0.5) = 100 ms to itself.
      val spacing = 100.millis
      val paced = fast.copy(limitWindow = 15.seconds)

      "shares the limits per IP address with the client of an account" >> {
        val crypto = CryptoImpl.create[IO]
        VirtualTime.run(
          for {
            http <- server(respond = _ => answer("""{"symbols":[]}"""))
            ipBuckets <- BinanceIpBuckets.create[IO, IO]
            market <- BinanceMarketImpl.create[IO, IO](paced, http.backend, ipBuckets)
            api <- BinanceApiImpl.create[IO, IO](
              ApiKey("api-key"),
              SecretKey("secret-key"),
              paced,
              crypto,
              http.backend,
              ipBuckets
            )
            _ <- List(market.exchangeInfo, api.exchangeInfo, market.exchangeInfo).parSequence
            calls <- http.calls
          } yield VirtualTime.gaps(calls.filter(_.path == ExchangeInfo).map(_.at)) must_==
            List(spacing, spacing)
        )
      }
    }
  }
}
