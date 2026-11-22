package st.btu.trading.pillar.gateways.binance

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import io.circe.{Json, JsonObject}
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import sttp.client3.{SttpClientException, UriContext, basicRequest}
import sttp.model.StatusCode
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.security.{Bouncy, CryptoImpl}
import st.btu.trading.pillar.testkit.BinanceFakes.{Call as _, *}
import st.btu.trading.pillar.testkit.BinanceHttpFakes.*
import st.btu.trading.pillar.testkit.{LogFakes, VirtualTime}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.io.IOException
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.*

class BinanceApiImplSpec extends Specification with CatsEffect {

  private implicit val bouncy: Bouncy = new Bouncy {}
  private val crypto = CryptoImpl.create[IO]

  private val plainLogging: Logging[IO] = Logging.Make.plain[IO].forService[BinanceApi[IO]]

  private val apiKey = ApiKey("api-key-0123")
  private val secretKey = SecretKey("secret-key-4567")

  // Calls that wait next to nothing for their turn, and for a retry.
  private val fast = BinanceApiConfig(
    limitWindow = 1.milli,
    retryDelay = 1.milli,
    rateLimitDelay = 1.milli,
    maxAttempts = 3
  )

  // The signature that an independent implementation makes of the query, which is what Binance
  // checks the request against.
  private def hmacOf(query: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secretKey.value.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(query.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
  }

  private final case class Fixture(
    api: BinanceApi[IO],
    calls: IO[List[Call]],
    ipBuckets: BinanceIpBuckets[IO]
  ) {
    def paths: IO[List[String]] = calls.map(_.map(_.path))

    def callsTo(path: String): IO[List[Call]] = calls.map(_.filter(_.path == path))
  }

  // Builds a client over a server that answers each call with what `respond` makes of it, and the
  // call to `/api/v3/time` with the clock of a server that is `offsets` milliseconds ahead of this
  // one, the next offset after each call and the last again after that.
  private def fixture(
    config: BinanceApiConfig = fast,
    offsets: List[Long] = List(0L),
    logging: Logging[IO] = plainLogging,
    sharing: Option[BinanceIpBuckets[IO]] = None,
    respond: Call => Reply = _ => answer("[]")
  ): IO[Fixture] = {
    implicit val logs: Logs[IO, IO] = Logs.const[IO, IO](logging)
    for {
      http <- server(offsets, respond)
      ipBuckets <- sharing.fold(BinanceIpBuckets.create[IO, IO])(IO.pure)
      api <- BinanceApiImpl.create[IO, IO](
        apiKey,
        secretKey,
        config,
        crypto,
        http.backend,
        ipBuckets
      )
    } yield Fixture(api, http.calls, ipBuckets)
  }

  private def array(objects: Seq[JsonObject]): String =
    Json.arr(objects.map(Json.fromJsonObject)*).noSpaces

  private val time = "2026-03-10T08:00:00Z"
  private val from = at("2026-03-01T00:00:00Z")
  private val to = at("2026-03-09T23:59:59.999Z")

  private def depositsOf(count: Int): List[JsonObject] =
    (1 to count).toList.map(i => deposit(s"d$i", "BTC", "0.1", time))

  private val MyTrades = "/api/v3/myTrades"
  private val SpotAccount = "/api/v3/account"
  private val TickerPrice = "/api/v3/ticker/price"
  private val Time = "/api/v3/time"
  private val Restrictions = "/sapi/v1/account/apiRestrictions"
  private val Deposits = "/sapi/v1/capital/deposit/hisrec"
  private val Withdrawals = "/sapi/v1/capital/withdraw/history"
  private val FiatOrders = "/sapi/v1/fiat/orders"
  private val FiatPayments = "/sapi/v1/fiat/payments"
  private val Convert = "/sapi/v1/convert/tradeFlow"
  private val AssetDividend = "/sapi/v1/asset/assetDividend"
  private val Dribblet = "/sapi/v1/asset/dribblet"
  private val Klines = "/api/v3/klines"

  "BinanceApiImpl" >> {
    "signed calls" >> {
      "carry the key in a header and a signature over the query that was sent" >> {
        for {
          f <- fixture(respond = _ => answer("""{"enableReading":true}"""))
          _ <- f.api.apiRestrictions
          calls <- f.callsTo(Restrictions)
        } yield {
          val call = calls.head
          call.key must beSome("api-key-0123")
          call.host must_== ApiHost
          call.params.keySet must contain(allOf("recvWindow", "timestamp", "signature"))
          call.params("recvWindow") must_== "10000"
          call.signature must_== hmacOf(call.signedQuery)
        }
      }

      "put the signature last in the query" >> {
        for {
          f <- fixture(respond = _ => answer("[]"))
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          calls <- f.callsTo(MyTrades)
        } yield calls.head.query must beMatching(".*&signature=[0-9a-f]{64}")
      }

      "send the query as it was signed, percent-encoded, however sttp would encode it" >> {
        for {
          f <- fixture(respond = _ => answer("[]"))
          _ <- f.api.myTrades(symbol("A B/C+D:E"), BinanceTradeId(0))
          calls <- f.callsTo(MyTrades)
        } yield {
          val call = calls.head
          call.signedQuery must startWith("symbol=A%20B%2FC%2BD%3AE&")
          call.signature must_== hmacOf(call.signedQuery)
        }
      }

      "move the timestamp by the offset to Binance's clock" >> {
        // the server is 5 seconds ahead
        VirtualTime.run(for {
          f <- fixture(offsets = List(5000L))
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          calls <- f.callsTo(MyTrades)
          now <- IO.realTime.map(_.toMillis)
        } yield calls.head.params("timestamp").toLong - now must_== 5000L)
      }

      "read the offset once, however many calls are made" >> {
        for {
          f <- fixture()
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          _ <- f.api.myTrades(symbol("ETHUSDT"), BinanceTradeId(0))
          _ <- f.api.apiRestrictions.attempt
          times <- f.callsTo(Time)
        } yield times.size must_== 1
      }

      "read the offset again when Binance refuses the timestamp, and try again with the new one" >> {
        val outside = "Timestamp for this request is outside of the recvWindow."
        VirtualTime.run(for {
          f <- fixture(
            offsets = List(5000L, 9000L),
            respond = call => if (call.number == 1) refusal(-1021, outside) else answer("[]")
          )
          trades <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          paths <- f.paths
          calls <- f.callsTo(MyTrades)
          now <- IO.realTime.map(_.toMillis)
        } yield {
          trades must beEmpty
          paths must_== List(Time, MyTrades, Time, MyTrades)
          calls(1).params("timestamp").toLong - now must_== 9000L
        })
      }

      "refuse the timestamp for good when Binance keeps refusing it after one new reading" >> {
        for {
          f <- fixture(respond = _ => refusal(-1021, "Timestamp outside of the recvWindow."))
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
          paths <- f.paths
        } yield {
          failure must beLeft[Throwable].like { case BinanceError.Refused(-1021, _) => ok }
          paths must_== List(Time, MyTrades, Time, MyTrades)
        }
      }
    }

    "calls that need no key" >> {
      "carry neither the key nor a signature, and the market data goes to the market data host" >> {
        for {
          f <- fixture(respond =
            call => if (call.path == Klines) answer("[]") else answer("""{"symbols":[]}""")
          )
          _ <- f.api.exchangeInfo
          _ <- f.api.minuteClose(symbol("BTCUSDC"), at("2026-03-10T12:34:00Z"))
          calls <- f.calls
        } yield {
          calls.map(_.key) must_== List(None, None)
          calls.map(_.host) must_== List(ApiHost, MarketHost)
          calls.exists(_.query.contains("signature")) must beFalse
        }
      }

      "reach the market data host for the daily bars and the last price, with no key either" >> {
        for {
          f <- fixture(respond =
            call =>
              if (call.path == TickerPrice)
                answer("""{"symbol":"BTCUSDC","price":"43251.01"}""")
              else answer("[]")
          )
          _ <- f.api.dailyBars(
            symbol("BTCUSDC"),
            LocalDate.of(2026, 3, 9),
            LocalDate.of(2026, 3, 10)
          )
          _ <- f.api.lastPrice(symbol("BTCUSDC"))
          calls <- f.calls
        } yield {
          calls.map(_.host) must_== List(MarketHost, MarketHost)
          calls.map(_.key) must_== List(None, None)
          calls.exists(_.query.contains("signature")) must beFalse
        }
      }

      "do not read the offset to Binance's clock" >> {
        for {
          f <- fixture(respond = _ => answer("""{"symbols":[]}"""))
          _ <- f.api.exchangeInfo
          paths <- f.paths
        } yield paths must_== List("/api/v3/exchangeInfo")
      }
    }

    "apiRestrictions" >> {
      "gives the permissions of the key, with every flag named enable or permits besides reading" >> {
        val body =
          """{"ipRestrict":true,"createTime":1698645219000,"enableInternalTransfer":false,
            |"enableReading":true,"enableWithdrawals":true,"enableMargin":false,"enableFutures":false,
            |"permitsUniversalTransfer":false,"enableVanillaOptions":false,"enableFixApiTrade":false,
            |"enableFixReadOnly":true,"enableSpotAndMarginTrading":false,
            |"enablePortfolioMarginTrading":false}""".stripMargin
        for {
          f <- fixture(respond = _ => answer(body))
          permissions <- f.api.apiRestrictions
        } yield permissions must_== BinanceKeyPermissions(
          ipRestricted = true,
          enableReading = true,
          otherFlags = Map(
            "enableInternalTransfer" -> false,
            "enableWithdrawals" -> true,
            "enableMargin" -> false,
            "enableFutures" -> false,
            "permitsUniversalTransfer" -> false,
            "enableVanillaOptions" -> false,
            "enableFixApiTrade" -> false,
            "enableSpotAndMarginTrading" -> false,
            "enablePortfolioMarginTrading" -> false
          )
        )
      }

      "takes a key that does not say it is restricted to an IP address for an unrestricted one" >> {
        for {
          f <- fixture(respond = _ => answer("""{"enableReading":true}"""))
          permissions <- f.api.apiRestrictions
        } yield permissions.ipRestricted must beFalse
      }

      "fails when the answer does not say whether the key may read" >> {
        for {
          f <- fixture(respond = _ => answer("""{"ipRestrict":true}"""))
          failure <- f.api.apiRestrictions.attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "exchangeInfo" >> {
      "gives each pair with its assets, whatever its status" >> {
        val body =
          """{"timezone":"UTC","serverTime":1,"rateLimits":[],"symbols":[
            |{"symbol":"BTCUSDT","status":"TRADING","baseAsset":"BTC","quoteAsset":"USDT","filters":[]},
            |{"symbol":"LUNAUSDT","status":"BREAK","baseAsset":"LUNA","quoteAsset":"USDT"}]}""".stripMargin
        for {
          f <- fixture(respond = _ => answer(body))
          pairs <- f.api.exchangeInfo
        } yield pairs must_== List(pairOf("BTC", "USDT"), pairOf("LUNA", "USDT"))
      }

      "fails when the answer has no symbols" >> {
        for {
          f <- fixture(respond = _ => answer("""{"timezone":"UTC"}"""))
          failure <- f.api.exchangeInfo.attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "delistSchedule" >> {
      "gives each pair announced once, whatever the dates" >> {
        val body =
          """[{"delistTime":1686161202000,"symbols":["ADAUSDT","BNBUSDT"]},
            |{"delistTime":1686222232000,"symbols":["BNBUSDT","ETHUSDT"]}]""".stripMargin
        for {
          f <- fixture(respond = _ => answer(body))
          announced <- f.api.delistSchedule
        } yield announced must_== List(symbol("ADAUSDT"), symbol("BNBUSDT"), symbol("ETHUSDT"))
      }
    }

    "coins" >> {
      "gives the name of each coin by its code" >> {
        val body =
          """[{"coin":"BTC","depositAllEnable":true,"name":"Bitcoin","networkList":[]},
            |{"coin":"ETH","name":"Ethereum"}]""".stripMargin
        for {
          f <- fixture(respond = _ => answer(body))
          names <- f.api.coins
        } yield names must_== Map(asset("BTC") -> "Bitcoin", asset("ETH") -> "Ethereum")
      }
    }

    "myTrades" >> {
      "asks for a page of a pair from an id, and gives the trades as Binance sent them" >> {
        val sent = List(
          trade("BTCUSDT", 5, time, "50000", "0.1", "5000", true),
          trade("BTCUSDT", 6, time, "50010", "0.1", "5001", false)
        )
        for {
          f <- fixture(respond = _ => answer(array(sent)))
          trades <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(5))
          calls <- f.callsTo(MyTrades)
        } yield {
          trades must_== sent
          calls.head.params.view.filterKeys(Set("symbol", "fromId", "limit")).toMap must_==
            Map("symbol" -> "BTCUSDT", "fromId" -> "5", "limit" -> "1000")
        }
      }

      "gives no trades for a pair that Binance does not know" >> {
        for {
          f <- fixture(respond = _ => refusal(-1121, "Invalid symbol."))
          trades <- f.api.myTrades(symbol("LUNAUSDT"), BinanceTradeId(0))
        } yield trades must beEmpty
      }

      "keeps a refusal for any other reason" >> {
        for {
          f <- fixture(respond = _ => refusal(-1022, "Signature for this request is not valid."))
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
        } yield failure must beLeft[Throwable].like { case BinanceError.Refused(-1022, _) => ok }
      }
    }

    "deposits and withdrawals" >> {
      "are read a page at a time from the offset, up to a short page, in the window given" >> {
        val first = depositsOf(1000)
        val second = depositsOf(3).map(_.add("note", Json.fromString("last")))
        for {
          f <- fixture(respond =
            call =>
              if (call.params("offset") == "0") answer(array(first)) else answer(array(second))
          )
          read <- f.api.deposits(from, to)
          calls <- f.callsTo(Deposits)
        } yield {
          read must_== first ++ second
          calls.map(_.params("offset")) must_== List("0", "1000")
          calls.map(_.params("limit")).distinct must_== List("1000")
          calls.head.params("startTime") must_== from.toEpochMilli.toString
          calls.head.params("endTime") must_== to.toEpochMilli.toString
        }
      }

      "stop after a page that is short" >> {
        for {
          f <- fixture(respond = _ => answer(array(depositsOf(999))))
          read <- f.api.deposits(from, to)
          calls <- f.callsTo(Deposits)
        } yield {
          read.size must_== 999
          calls.size must_== 1
        }
      }

      "are asked of their own endpoint, withdrawals included" >> {
        val sent = List(withdrawal("w1", "BTC", "0.5", "0.0005", time))
        for {
          f <- fixture(respond = _ => answer(array(sent)))
          read <- f.api.withdrawals(from, to)
          calls <- f.callsTo(Withdrawals)
        } yield {
          read must_== sent
          calls.size must_== 1
        }
      }
    }

    "fiat orders and payments" >> {
      def rows(count: Int): List[JsonObject] =
        (1 to count).toList.map(i => fiatOrder(s"o$i", "EUR", "10", "10", "0", time))

      def envelope(data: List[JsonObject]): String =
        s"""{"code":"000000","message":"success","data":${array(
            data
          )},"total":${data.size},"success":true}"""

      "are read a page at a time, 500 rows to a page, up to a short page" >> {
        for {
          f <- fixture(respond =
            call =>
              if (call.params("page") == "1") answer(envelope(rows(500)))
              else answer(envelope(rows(2)))
          )
          read <- f.api.fiatOrders(FiatOrderDirection.Deposit, from, to)
          calls <- f.callsTo(FiatOrders)
        } yield {
          read.size must_== 502
          calls.map(_.params("page")) must_== List("1", "2")
          calls.map(_.params("rows")).distinct must_== List("500")
        }
      }

      "ask for deposits or withdrawals, and for purchases or sales, by the type of transaction" >> {
        for {
          f <- fixture(respond = _ => answer(envelope(Nil)))
          _ <- f.api.fiatOrders(FiatOrderDirection.Deposit, from, to)
          _ <- f.api.fiatOrders(FiatOrderDirection.Withdrawal, from, to)
          _ <- f.api.fiatPayments(FiatPaymentDirection.Buy, from, to)
          _ <- f.api.fiatPayments(FiatPaymentDirection.Sell, from, to)
          orders <- f.callsTo(FiatOrders)
          payments <- f.callsTo(FiatPayments)
        } yield {
          orders.map(_.params("transactionType")) must_== List("0", "1")
          payments.map(_.params("transactionType")) must_== List("0", "1")
          orders.head.params("beginTime") must_== from.toEpochMilli.toString
          orders.head.params("endTime") must_== to.toEpochMilli.toString
        }
      }

      "give no rows when the answer leaves its data out" >> {
        for {
          f <- fixture(respond =
            _ => answer("""{"code":"000000","message":"success","total":0,"success":true}""")
          )
          read <- f.api.fiatPayments(FiatPaymentDirection.Buy, from, to)
        } yield read must beEmpty
      }

      "fail with Binance's code when the answer is not a success" >> {
        for {
          f <- fixture(respond =
            _ => answer("""{"code":"000002","message":"illegal parameter","success":false}""")
          )
          failure <- f.api.fiatOrders(FiatOrderDirection.Deposit, from, to).attempt
        } yield failure must beLeft[Throwable].like { case BinanceError.Refused(2, _) => ok }
      }
    }

    "convertTradeFlow" >> {
      def page(orders: List[JsonObject], moreData: Boolean): String =
        s"""{"list":${array(orders)},"startTime":1,"endTime":2,"limit":1000,"moreData":$moreData}"""

      val first = convert("940708407462087201", "USDT", "20", "BNB", "0.06", time)
      val second = convert("940708407462087202", "BNB", "0.06", "USDT", "21", time)

      "reads a window that fits an answer in one call" >> {
        for {
          f <- fixture(respond = _ => answer(page(List(first), moreData = false)))
          read <- f.api.convertTradeFlow(from, to)
          calls <- f.callsTo(Convert)
        } yield {
          read must_== List(first)
          calls.size must_== 1
          calls.head.params("limit") must_== "1000"
        }
      }

      "reads a window that holds more orders than an answer as two halves" >> {
        val start = from.toEpochMilli
        val end = to.toEpochMilli
        val middle = start + (end - start) / 2
        for {
          f <- fixture(respond =
            call =>
              if (call.number == 1) answer(page(List(first), moreData = true))
              else if (call.params("startTime") == start.toString)
                answer(page(List(first), moreData = false))
              else answer(page(List(second), moreData = false))
          )
          read <- f.api.convertTradeFlow(from, to)
          calls <- f.callsTo(Convert)
        } yield {
          read must_== List(first, second)
          calls.map(c => (c.params("startTime"), c.params("endTime"))) must_== List(
            (start.toString, end.toString),
            (start.toString, middle.toString),
            ((middle + 1).toString, end.toString)
          )
        }
      }

      "gives up on a single millisecond that holds more orders than an answer" >> {
        for {
          f <- fixture(respond = _ => answer(page(List(first), moreData = true)))
          failure <- f.api.convertTradeFlow(from, from).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "assetDividends" >> {
      def page(rows: List[JsonObject]): String = s"""{"rows":${array(rows)},"total":${rows.size}}"""

      def rowsOf(count: Int, firstId: Long = 1): List[JsonObject] =
        (0 until count).toList.map(i => distribution(firstId + i, "BHFT", "10", time))

      val start = from.toEpochMilli
      val end = to.toEpochMilli
      val middle = start + (end - start) / 2

      def window(call: Call): (Long, Long) =
        (call.params("startTime").toLong, call.params("endTime").toLong)

      "asks for the window with a limit of 500 rows, and gives the rows as Binance sent them" >> {
        val sent = rowsOf(2)
        for {
          f <- fixture(respond = _ => answer(page(sent)))
          read <- f.api.assetDividends(from, to)
          calls <- f.callsTo(AssetDividend)
        } yield {
          read must_== sent
          calls.size must_== 1
          calls.head.method must_== "GET"
          window(calls.head) must_== (start, end)
          calls.head.params("limit") must_== "500"
          calls.head.key must beSome("api-key-0123")
        }
      }

      "gives no rows when the answer leaves them out" >> {
        for {
          f <- fixture(respond = _ => answer("""{"total":0}"""))
          read <- f.api.assetDividends(from, to)
        } yield read must beEmpty
      }

      "reads a window that holds 499 rows in one call" >> {
        for {
          f <- fixture(respond = _ => answer(page(rowsOf(499))))
          read <- f.api.assetDividends(from, to)
          calls <- f.callsTo(AssetDividend)
        } yield {
          read.size must_== 499
          calls.size must_== 1
        }
      }

      "reads a window whose answer holds 500 rows again as two halves" >> {
        val first = rowsOf(2)
        val second = rowsOf(3, firstId = 10)
        for {
          f <- fixture(respond =
            call =>
              if (call.number == 1) answer(page(rowsOf(500)))
              else if (window(call)._1 == start) answer(page(first))
              else answer(page(second))
          )
          read <- f.api.assetDividends(from, to)
          calls <- f.callsTo(AssetDividend)
        } yield {
          read must_== first ++ second
          calls.map(window) must_== List((start, end), (start, middle), (middle + 1, end))
        }
      }

      "reads a half that is still full as two quarters" >> {
        val quarter = (middle - start) / 2 + start
        val rows = Map(
          (start, end) -> rowsOf(500),
          (start, middle) -> rowsOf(500),
          (start, quarter) -> rowsOf(1, firstId = 1),
          (quarter + 1, middle) -> rowsOf(1, firstId = 2),
          (middle + 1, end) -> rowsOf(1, firstId = 3)
        )
        for {
          f <- fixture(respond = call => answer(page(rows.getOrElse(window(call), Nil))))
          read <- f.api.assetDividends(from, to)
          calls <- f.callsTo(AssetDividend)
        } yield {
          read must_== rowsOf(1, 1) ++ rowsOf(1, 2) ++ rowsOf(1, 3)
          calls.map(window) must_== List(
            (start, end),
            (start, middle),
            (start, quarter),
            (quarter + 1, middle),
            (middle + 1, end)
          )
        }
      }

      "gives up on a single millisecond whose answer still holds 500 rows" >> {
        for {
          f <- fixture(respond = _ => answer(page(rowsOf(500))))
          failure <- f.api.assetDividends(from, from).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }

      "fails when the rows are not a list of objects" >> {
        for {
          f <- fixture(respond = _ => answer("""{"rows":[1,2]}"""))
          failure <- f.api.assetDividends(from, to).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }

      "fails when the answer is not an object" >> {
        for {
          f <- fixture(respond = _ => answer("[]"))
          failure <- f.api.assetDividends(from, to).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "dustLog" >> {
      def page(conversions: List[JsonObject]): String =
        s"""{"total":${conversions.size},"userAssetDribblets":${array(conversions)}}"""

      def conversionsOf(count: Int, firstId: Long = 1): List[JsonObject] =
        (0 until count).toList.map { i =>
          val id = firstId + i
          dustConversion(id, time, dustDetail(id, "USDT", "0.0009", "0.000441", "0.000009", time))
        }

      val start = from.toEpochMilli
      val end = to.toEpochMilli
      val middle = start + (end - start) / 2

      def window(call: Call): (Long, Long) =
        (call.params("startTime").toLong, call.params("endTime").toLong)

      "asks for the window and no limit, and gives the conversions as Binance sent them" >> {
        val sent = conversionsOf(2)
        for {
          f <- fixture(respond = _ => answer(page(sent)))
          read <- f.api.dustLog(from, to)
          calls <- f.callsTo(Dribblet)
        } yield {
          read must_== sent
          calls.size must_== 1
          calls.head.method must_== "GET"
          window(calls.head) must_== (start, end)
          calls.head.params.contains("limit") must beFalse
          calls.head.key must beSome("api-key-0123")
        }
      }

      "gives no conversions when the answer leaves them out" >> {
        for {
          f <- fixture(respond = _ => answer("""{"total":0}"""))
          read <- f.api.dustLog(from, to)
        } yield read must beEmpty
      }

      "reads a window that holds 99 conversions in one call" >> {
        for {
          f <- fixture(respond = _ => answer(page(conversionsOf(99))))
          read <- f.api.dustLog(from, to)
          calls <- f.callsTo(Dribblet)
        } yield {
          read.size must_== 99
          calls.size must_== 1
        }
      }

      "reads a window whose answer holds 100 conversions again as two halves" >> {
        val first = conversionsOf(2)
        val second = conversionsOf(3, firstId = 10)
        for {
          f <- fixture(respond =
            call =>
              if (call.number == 1) answer(page(conversionsOf(100)))
              else if (window(call)._1 == start) answer(page(first))
              else answer(page(second))
          )
          read <- f.api.dustLog(from, to)
          calls <- f.callsTo(Dribblet)
        } yield {
          read must_== first ++ second
          calls.map(window) must_== List((start, end), (start, middle), (middle + 1, end))
        }
      }

      "gives up on a single millisecond whose answer still holds 100 conversions" >> {
        for {
          f <- fixture(respond = _ => answer(page(conversionsOf(100))))
          failure <- f.api.dustLog(from, from).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }

      "fails when the conversions are not a list of objects" >> {
        for {
          f <- fixture(respond = _ => answer("""{"userAssetDribblets":"none"}"""))
          failure <- f.api.dustLog(from, to).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "minuteClose" >> {
      val start = at("2026-03-10T12:34:00Z")
      val bar =
        """[[1773146040000,"0.01634790","0.80000000","0.01575800","0.01577100","148976.11",
          |1773146099999,"2434.19",308,"1756.87","28.46","0"]]""".stripMargin

      "gives the close of the bar that starts at the minute" >> {
        for {
          f <- fixture(respond = _ => answer(bar))
          close <- f.api.minuteClose(symbol("BTCUSDC"), start)
          calls <- f.callsTo(Klines)
        } yield {
          close must beSome(BigDecimal("0.01577100"))
          calls.head.params.view
            .filterKeys(Set("symbol", "interval", "startTime", "endTime", "limit"))
            .toMap must_== Map(
            "symbol" -> "BTCUSDC",
            "interval" -> "1m",
            "startTime" -> start.toEpochMilli.toString,
            "endTime" -> (start.toEpochMilli + 59999).toString,
            "limit" -> "1"
          )
        }
      }

      "gives nothing when the pair had no bar in the minute" >> {
        for {
          f <- fixture(respond = _ => answer("[]"))
          close <- f.api.minuteClose(symbol("BTCUSDC"), start)
        } yield close must beNone
      }

      "gives nothing for a pair Binance does not know" >> {
        for {
          f <- fixture(respond = _ => refusal(-1121, "Invalid symbol."))
          close <- f.api.minuteClose(symbol("NOPE"), start)
        } yield close must beNone
      }
    }

    "spotBalances" >> {
      val body =
        """{"makerCommission":15,"canTrade":true,"balances":[
          |{"asset":"BTC","free":"0.50000000","locked":"0.25000000"},
          |{"asset":"ETH","free":"0.00000000","locked":"0.00000000"},
          |{"asset":"USDT","free":"100.5","locked":"0"}],"permissions":["SPOT"],"uid":354937868}""".stripMargin

      "gives the free and the locked amount of each asset together, leaving out an asset with none" >> {
        for {
          f <- fixture(respond = _ => answer(body))
          balances <- f.api.spotBalances
        } yield balances must_== Map(
          asset("BTC") -> BigDecimal("0.75"),
          asset("USDT") -> BigDecimal("100.5")
        )
      }

      "is a signed read of the spot account that asks to omit the zero balances" >> {
        for {
          f <- fixture(respond = _ => answer(body))
          _ <- f.api.spotBalances
          calls <- f.callsTo(SpotAccount)
        } yield {
          val call = calls.head
          call.host must_== ApiHost
          call.method must_== "GET"
          call.key must beSome("api-key-0123")
          call.params("omitZeroBalances") must_== "true"
          call.signature must_== hmacOf(call.signedQuery)
        }
      }

      "fails when the answer has no balances" >> {
        for {
          f <- fixture(respond = _ => answer("""{"canTrade":true}"""))
          failure <- f.api.spotBalances.attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }

      "fails when an amount is no number" >> {
        for {
          f <- fixture(respond =
            _ => answer("""{"balances":[{"asset":"BTC","free":"plenty","locked":"0"}]}""")
          )
          failure <- f.api.spotBalances.attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "fundingBalances" >> {
      val body =
        """[{"asset":"USDT","free":"10","locked":"1","freeze":"0.5","withdrawing":"0.25",
          |"btcValuation":"0.0001"},
          |{"asset":"BNB","free":"0","locked":"0","freeze":"0","withdrawing":"0"}]""".stripMargin

      "gives the free, locked, frozen and withdrawing amount of each asset together, leaving out an asset with none" >> {
        for {
          f <- fixture(respond = _ => answer(body))
          balances <- f.api.fundingBalances
        } yield balances must_== Map(asset("USDT") -> BigDecimal("11.75"))
      }

      "gives nothing when the wallet holds nothing" >> {
        for {
          f <- fixture(respond = _ => answer("[]"))
          balances <- f.api.fundingBalances
        } yield balances must beEmpty
      }

      "is a POST that sends its signed query string as the query, with no body" >> {
        for {
          f <- fixture(respond = _ => answer(body))
          _ <- f.api.fundingBalances
          calls <- f.callsTo("/sapi/v1/asset/get-funding-asset")
        } yield {
          val call = calls.head
          call.host must_== ApiHost
          call.method must_== "POST"
          call.hasBody must beFalse
          call.key must beSome("api-key-0123")
          call.params.keySet must contain(allOf("recvWindow", "timestamp", "signature"))
          call.signature must_== hmacOf(call.signedQuery)
        }
      }

      "fails when the answer is not a list" >> {
        for {
          f <- fixture(respond = _ => answer("""{"asset":"USDT"}"""))
          failure <- f.api.fundingBalances.attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }
    }

    "refusals" >> {
      "carry Binance's code and message" >> {
        for {
          f <- fixture(respond =
            _ =>
              refusal(
                -2015,
                "Invalid API-key, IP, or permissions for action.",
                StatusCode.Unauthorized
              )
          )
          failure <- f.api.apiRestrictions.attempt
          calls <- f.callsTo(Restrictions)
        } yield {
          failure must beLeft[Throwable](
            BinanceError.Refused(-2015, "Invalid API-key, IP, or permissions for action.")
          )
          calls.size must_== 1
        }
      }

      "have the keys and the signature taken out of Binance's message" >> {
        for {
          f <- fixture(respond =
            call =>
              refusal(
                -1022,
                s"Signature ${call.signature} of key api-key-0123 and secret secret-key-4567 is not valid. " +
                  s"Query ${call.signedQuery}"
              )
          )
          failure <- f.api.apiRestrictions.attempt
        } yield failure must beLeft[Throwable].like { case error =>
          val message = error.getMessage
          message must contain("-1022")
          message must not(contain("api-key-0123"))
          message must not(contain("secret-key-4567"))
          message must not(contain("signature"))
          message must not(contain("timestamp"))
        }
      }

      "are followed by no new attempt" >> {
        for {
          f <- fixture(respond = _ => refusal(-1022, "Signature for this request is not valid."))
          _ <- f.api.apiRestrictions.attempt
          calls <- f.callsTo(Restrictions)
        } yield calls.size must_== 1
      }
    }

    "answers that ask to try again" >> {
      "429 is tried again after a wait, and the answer is given once it comes" >> {
        for {
          f <- fixture(respond =
            call =>
              if (call.number == 1) Responds("slow down", StatusCode.TooManyRequests)
              else answer("[]")
          )
          trades <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          calls <- f.callsTo(MyTrades)
        } yield {
          trades must beEmpty
          calls.size must_== 2
        }
      }

      "waits longer after 429 than after a server error" >> {
        val config = fast.copy(retryDelay = 20.millis, rateLimitDelay = 300.millis)
        VirtualTime.run(for {
          f <- fixture(
            config,
            respond = call =>
              call.number match {
                case 1 => Responds("slow down", StatusCode.TooManyRequests)
                case 2 => Responds("oops", StatusCode.BadGateway)
                case _ => answer("[]")
              }
          )
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          times <- f.callsTo(MyTrades).map(_.map(_.at))
        } yield VirtualTime.gaps(times) must_== List(config.rateLimitDelay, config.retryDelay))
      }

      "server errors" >> {
        Fragments.foreach(List(500, 502, 503, 504)) { status =>
          s"$status is tried again, up to the attempts allowed, and then reported with its status" >> {
            for {
              f <- fixture(respond = _ => Responds("oops", statusOf(status)))
              failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
              calls <- f.callsTo(MyTrades)
            } yield {
              failure must beLeft[Throwable](BinanceError.RetriesExhausted(status, 3))
              calls.size must_== 3
            }
          }
        }
      }

      "429 for good is reported as 429 after the attempts allowed" >> {
        for {
          f <- fixture(respond = _ => Responds("slow", StatusCode.TooManyRequests))
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
        } yield failure must beLeft[Throwable](BinanceError.RetriesExhausted(429, 3))
      }
    }

    "a ban of the IP address" >> {
      "fails the call at once" >> {
        for {
          f <- fixture(respond = _ => Responds("banned", statusOf(418)))
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
          calls <- f.callsTo(MyTrades)
        } yield {
          failure must beLeft[Throwable](BinanceError.Banned("/api/v3/myTrades"))
          calls.size must_== 1
        }
      }
    }

    "answers that are none of those Binance documents" >> {
      "an HTTP status that waiting does not change" >> {
        Fragments.foreach(List(403, 404)) { status =>
          s"$status ends the call at once, as an unexpected response" >> {
            for {
              f <- fixture(respond = _ => Responds("<html>", statusOf(status)))
              failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
              calls <- f.callsTo(MyTrades)
            } yield {
              failure must beLeft[Throwable](
                BinanceError.UnexpectedResponse(s"HTTP status $status")
              )
              calls.size must_== 1
            }
          }
        }
      }

      "a success that is not JSON fails as an unexpected response" >> {
        for {
          f <- fixture(respond = _ => answer("<html>"))
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }

      "a list of trades that holds something else fails as an unexpected response" >> {
        for {
          f <- fixture(respond = _ => answer("[1,2]"))
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
        } yield failure must beLeft[Throwable].like { case _: BinanceError.UnexpectedResponse =>
          ok
        }
      }

      "an error that comes with a success status is a refusal" >> {
        for {
          f <- fixture(respond =
            _ =>
              answer("""{"code":-2015,"msg":"Invalid API-key, IP, or permissions for action."}""")
          )
          failure <- f.api.apiRestrictions.attempt
        } yield failure must beLeft[Throwable].like { case BinanceError.Refused(-2015, _) => ok }
      }
    }

    "calls that get no answer" >> {
      // How an HTTP client reports a refused connection: in an exception whose message is the
      // request, and so a URL with the signature, around the exception that tells what went wrong.
      def connectFailure(cause: Exception): Exception =
        new SttpClientException.ConnectException(
          basicRequest.get(uri"https://api.binance.com/api/v3/myTrades?signature=abcdef"),
          cause
        )

      "are tried again, and the answer is given once the server answers" >> {
        for {
          f <- fixture(respond =
            call =>
              if (call.number < 3)
                Breaks(connectFailure(new ConnectException("Connection refused")))
              else answer("[]")
          )
          trades <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          calls <- f.callsTo(MyTrades)
        } yield {
          trades must beEmpty
          calls.size must_== 3
        }
      }

      "are given up after the attempts allowed, with the failure of the last" >> {
        for {
          f <- fixture(respond =
            _ => Breaks(connectFailure(new ConnectException("Connection refused")))
          )
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
          calls <- f.callsTo(MyTrades)
        } yield {
          failure must beLeft[Throwable](
            BinanceError.Unreachable("java.net.ConnectException: Connection refused")
          )
          calls.size must_== 3
        }
      }

      "are described by their cause, with the query, the signature and the keys taken out of it" >> {
        for {
          f <- fixture(respond =
            call =>
              Breaks(
                connectFailure(
                  new IOException(
                    s"stream ended on ${call.query} for key api-key-0123 and secret secret-key-4567"
                  )
                )
              )
          )
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
        } yield failure must beLeft[Throwable].like { case error =>
          val message = error.getMessage
          message must contain("java.io.IOException")
          message must not(contain("signature"))
          message must not(contain("timestamp"))
          message must not(contain("api-key-0123"))
          message must not(contain("secret-key-4567"))
        }
      }

      "are described by the class alone when they have no cause, since their message may be the request" >> {
        for {
          f <- fixture(respond =
            _ => Breaks(new RuntimeException("request failed: api.binance.com?signature=abcdef"))
          )
          failure <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)).attempt
        } yield failure must beLeft[Throwable](
          BinanceError.Unreachable("java.lang.RuntimeException")
        )
      }
    }

    "logs" >> {
      "never hold the query, the signature or the keys" >> {
        for {
          logging <- LogFakes.recordingLogging
          scenarios <- List(
            fixture(respond = _ => answer(array(depositsOf(2))), logging = logging),
            fixture(respond = _ => refusal(-2015, "Invalid key."), logging = logging),
            fixture(respond = _ => Responds("oops", StatusCode.BadGateway), logging = logging),
            fixture(respond = _ => Breaks(new IOException("connection reset")), logging = logging),
            fixture(respond = _ => Responds("banned", statusOf(418)), logging = logging)
          ).sequence
          _ <- scenarios.traverse(_.api.withdrawals(from, to).attempt)
          logged <- logging.entries
          calls <- scenarios.flatTraverse(_.calls)
        } yield {
          logged must not(beEmpty)
          val secrets = List("api-key-0123", "secret-key-4567", "signature") ++
            calls.filter(_.query.contains("signature")).flatMap(c => List(c.signature))
          logged.filter(entry => secrets.exists(entry.contains)) must beEmpty
        }
      }
    }

    "pacing" >> {
      // A call of weight 20 on /api/v3 has 15 s * 20 / (6000 * 0.5) = 100 ms to itself, and a call
      // of weight 2 on the market data host 10 ms.
      val spacing = 100.millis
      val marketSpacing = 10.millis
      val paced = fast.copy(limitWindow = 15.seconds)

      def tradeCalls(f: Fixture): IO[List[FiniteDuration]] =
        f.callsTo(MyTrades).map(_.map(_.at))

      def readsAccount(call: Call): Reply =
        if (call.path == SpotAccount) answer("""{"balances":[]}""") else answer("[]")

      "keeps the calls of one bucket apart by the share of its limit that their weight takes" >> {
        VirtualTime.run(for {
          f <- fixture(paced)
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)) // reads the offset
          before <- tradeCalls(f).map(_.size)
          _ <- List.fill(3)(f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))).sequence
          times <- tradeCalls(f).map(_.drop(before))
        } yield VirtualTime.gaps(times) must_== List(spacing, spacing))
      }

      "gives a call of less weight a shorter wait" >> {
        VirtualTime.run(for {
          f <- fixture(paced)
          _ <- List
            .fill(5)(f.api.minuteClose(symbol("BTCUSDC"), at("2026-03-10T12:34:00Z")))
            .sequence
          bars <- f.callsTo(Klines).map(_.map(_.at))
        } yield VirtualTime.gaps(bars) must_== List.fill(4)(marketSpacing))
      }

      "keeps the calls of different buckets from waiting for one another" >> {
        VirtualTime.run(for {
          f <- fixture(paced)
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          started <- IO.monotonic
          _ <- f.api.deposits(from, to)
          waited <- IO.monotonic.map(_ - started)
        } yield waited must_== Duration.Zero)
      }

      "shares the limits per IP address between the clients of every account" >> {
        VirtualTime.run(for {
          first <- fixture(paced)
          second <- fixture(paced, sharing = Some(first.ipBuckets))
          // these read the offsets to Binance's clock
          _ <- first.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          _ <- second.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          _ <- List(first, second).parTraverse(_.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)))
          times <- List(first, second).flatTraverse(tradeCalls(_).map(_.drop(1)))
        } yield VirtualTime.gaps(times) must_== List(spacing))
      }

      "keeps a read of the spot account apart from the other calls of /api/v3 by its weight of 20" >> {
        VirtualTime.run(for {
          f <- fixture(paced, respond = readsAccount)
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0)) // reads the offset
          _ <- f.api.spotBalances
          _ <- f.api.myTrades(symbol("BTCUSDT"), BinanceTradeId(0))
          account <- f.callsTo(SpotAccount).map(_.map(_.at))
          trades <- tradeCalls(f)
        } yield trades.last - account.head must_== spacing)
      }

      "gives the funding wallet a bucket of its own, so that it does not wait for the spot account" >> {
        VirtualTime.run(for {
          f <- fixture(paced, respond = readsAccount)
          _ <- f.api.spotBalances
          started <- IO.monotonic
          _ <- f.api.fundingBalances
          waited <- IO.monotonic.map(_ - started)
        } yield waited must_== Duration.Zero)
      }

      "keeps the distributions apart by their weight of 10, in a bucket of their own" >> {
        // 15 s * 10 / (12000 * 0.5) = 25 ms
        VirtualTime.run(for {
          f <- fixture(paced, respond = _ => answer("""{"total":0}"""))
          _ <- f.api.assetDividends(from, to) // reads the offset
          _ <- List.fill(3)(f.api.assetDividends(from, to)).sequence
          times <- f.callsTo(AssetDividend).map(_.map(_.at).drop(1))
        } yield VirtualTime.gaps(times) must_== List(25.millis, 25.millis))
      }

      "gives the dust log a bucket of its own, so that it does not wait for the distributions" >> {
        VirtualTime.run(for {
          f <- fixture(paced, respond = _ => answer("""{"total":0}"""))
          _ <- f.api.assetDividends(from, to)
          started <- IO.monotonic
          _ <- f.api.dustLog(from, to)
          waited <- IO.monotonic.map(_ - started)
        } yield waited must_== Duration.Zero)
      }

      "keeps the dust log apart by its weight of 1" >> {
        // 15 s * 1 / (12000 * 0.5) = 2.5 ms
        VirtualTime.run(for {
          f <- fixture(paced, respond = _ => answer("""{"total":0}"""))
          _ <- f.api.dustLog(from, to) // reads the offset
          _ <- List.fill(3)(f.api.dustLog(from, to)).sequence
          times <- f.callsTo(Dribblet).map(_.map(_.at).drop(1))
        } yield VirtualTime.gaps(times) must_== List(2500.micros, 2500.micros))
      }

      "keeps the ticker price and the bars apart by the weight they share the market data bucket by" >> {
        VirtualTime.run(for {
          f <- fixture(
            paced,
            respond = call =>
              if (call.path == TickerPrice) answer("""{"symbol":"A","price":"1"}""")
              else answer("[]")
          )
          _ <- f.api.lastPrice(symbol("BTCUSDC"))
          _ <- f.api.minuteClose(symbol("BTCUSDC"), at("2026-03-10T12:34:00Z"))
          price <- f.callsTo(TickerPrice).map(_.map(_.at))
          bars <- f.callsTo(Klines).map(_.map(_.at))
        } yield bars.head - price.head must_== marketSpacing)
      }

      "counts the limits per account for each account on its own" >> {
        // A withdrawal history has 15 s * 18000 / (180000 * 0.9) = 1.7 s to itself.
        VirtualTime.run(for {
          first <- fixture(paced)
          second <- fixture(paced, sharing = Some(first.ipBuckets))
          // these read the offsets to Binance's clock
          _ <- first.api.deposits(from, to)
          _ <- second.api.deposits(from, to)
          _ <- List(first, second).parTraverse(_.api.withdrawals(from, to))
          times <- List(first, second).flatTraverse(_.callsTo(Withdrawals).map(_.map(_.at)))
        } yield VirtualTime.gaps(times) must_== List(Duration.Zero))
      }
    }
  }
}
