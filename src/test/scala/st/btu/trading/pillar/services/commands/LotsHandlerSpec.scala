package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Figi}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.tax.{Lot, TaxConfig}
import st.btu.trading.pillar.testkit.{Fakes, TaxFakes}

import java.time.{LocalDate, ZoneOffset}

class LotsHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val today = LocalDate.now(ZoneOffset.UTC)

  "LotsHandler" >> {
    "handle" >> {

      "replies with one line per open lot, marking a long-term (>= 3 years) one" >> {
        val longTermLot = Lot(Figi("F1"), 10, BigDecimal("102.5"), today.minusDays(1200))
        val shortTermLot = Lot(Figi("F1"), 5, BigDecimal(200), today.minusDays(10))
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
            s"10 @ 102.50 acquired ${today.minusDays(1200)} (1200 day(s), long-term)"
          )
          msg must contain(s"5 @ 200.00 acquired ${today.minusDays(10)} (10 day(s))")
          msg must not(contain("10 day(s), long-term"))
        }
      }

      "marks no lot long-term under a regime without a long-term exemption" >> {
        val longHeldLot = Lot(Figi("F1"), 10, BigDecimal("102.5"), today.minusDays(1200))
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
          s"10 @ 102.50 acquired ${today.minusDays(1200)} (1200 day(s))"
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
