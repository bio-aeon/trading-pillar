package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Currency}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.tax.{Lot, TaxConfig}
import st.btu.trading.pillar.testkit.{Fakes, TaxFakes}
import st.btu.trading.pillar.testkit.MarketDataFakes.instrumentKey
import st.btu.trading.pillar.testkit.TaxFakes.lot

import java.time.{LocalDate, ZoneOffset}

class LotsHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val today = LocalDate.now(ZoneOffset.UTC)

  private def replyFor(lots: List[Lot], ticker: String = "AAPL"): IO[String] =
    for {
      tgGateway <- Fakes.tgGateway()
      taxService <- TaxFakes.taxService(openLotsByTicker = Map((accountId, Ticker(ticker)) -> lots))
      handler = new LotsHandler[IO](tgGateway, taxService, accountId)
      _ <- handler.handle(chatId, List(ticker))
      messages <- tgGateway.sentMessages
    } yield messages.head.message

  "LotsHandler" >> {
    "handle" >> {

      "replies with one line per open lot, marking a long-term (>= 3 years) one" >> {
        val longTermLot = lot(instrumentKey("F1"), 10, BigDecimal("102.5"), today.minusDays(1200))
        val shortTermLot = lot(instrumentKey("F1"), 5, BigDecimal(200), today.minusDays(10))
        for {
          tgGateway <- Fakes.tgGateway()
          taxService <- TaxFakes.taxService(
            taxConfig = TaxConfig(),
            openLotsByTicker = Map((accountId, Ticker("AAPL")) -> List(longTermLot, shortTermLot))
          )
          handler = new LotsHandler[IO](tgGateway, taxService, accountId)
          _ <- handler.handle(chatId, List("aapl"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain(
            s"10 @ 102.50 USD acquired ${today.minusDays(1200)} (1200 day(s), long-term)"
          )
          msg must contain(s"5 @ 200.00 USD acquired ${today.minusDays(10)} (10 day(s))")
          msg must not(contain("10 day(s), long-term"))
        }
      }

      "marks no lot long-term under a regime without a long-term exemption" >> {
        val longHeldLot = lot(instrumentKey("F1"), 10, BigDecimal("102.5"), today.minusDays(1200))
        for {
          tgGateway <- Fakes.tgGateway()
          taxService <- TaxFakes.taxService(
            taxConfig = TaxConfig(longTermYears = None),
            openLotsByTicker = Map((accountId, Ticker("AAPL")) -> List(longHeldLot))
          )
          handler = new LotsHandler[IO](tgGateway, taxService, accountId)
          _ <- handler.handle(chatId, List("aapl"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_==
          s"10 @ 102.50 USD acquired ${today.minusDays(1200)} (1200 day(s))"
      }

      "names the currency the unit cost was paid in" >> {
        val bought =
          lot(instrumentKey("BTC"), BigDecimal("0.1"), 20000, today, currency = Currency("EUR"))

        replyFor(List(bought), "BTCUSDC").map(
          _ must_== s"0.1 @ 20000.00 EUR acquired $today (0 day(s))"
        )
      }

      "joins the parts of a unit cost paid in several currencies with a plus, ordered by currency code" >> {
        val mixed = Lot(
          instrumentKey("BTC"),
          2,
          Map(
            Currency("USDT") -> BigDecimal("0.4"),
            Currency("EUR") -> BigDecimal(20000),
            Currency("BNB") -> BigDecimal("0.00123")
          ),
          today
        )

        replyFor(List(mixed), "BTCUSDC").map(
          _ must_== s"2 @ 0.00123 BNB + 20000.00 EUR + 0.4 USDT acquired $today (0 day(s))"
        )
      }

      "shows a lot at no cost as 0, with no currency" >> {
        val free = Lot(instrumentKey("BTC"), 3, Map.empty, today)

        replyFor(List(free), "BTCUSDC").map(_ must_== s"3 @ 0 acquired $today (0 day(s))")
      }

      "prints a quantity as plain digits, without trailing zeros or an exponent" >> {
        val fraction = lot(instrumentKey("F1"), BigDecimal("0.300000000000000000"), 800, today)
        val whole = lot(instrumentKey("F1"), BigDecimal("2E+1"), 5, today)

        replyFor(List(fraction, whole)).map(
          _ must_==
            s"0.3 @ 800.00 USD acquired $today (0 day(s))\n20 @ 5.00 USD acquired $today (0 day(s))"
        )
      }

      "prints a unit cost below a cent in its significant digits" >> {
        val coin =
          lot(instrumentKey("F1"), 1000000, BigDecimal("0.000008230000000000"), today)

        replyFor(List(coin), "SHIB").map(
          _ must_== s"1000000 @ 0.00000823 USD acquired $today (0 day(s))"
        )
      }

      "replies with 'No open lots for SYM.' when the ticker has none" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          taxService <- TaxFakes.taxService()
          handler = new LotsHandler[IO](tgGateway, taxService, accountId)
          _ <- handler.handle(chatId, List("ZZZ"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No open lots for ZZZ."
      }

      "replies with usage when the ticker is missing" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          taxService <- TaxFakes.taxService()
          handler = new LotsHandler[IO](tgGateway, taxService, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== s"Usage: ${handler.usage}"
      }
    }
  }
}
