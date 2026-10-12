package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Portfolio
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.Fakes

class InfoHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(12345L)

  "InfoHandler" >> {
    "handle" >> {

      "formats portfolio positions as 'name - balance'" >> {
        val positions = List(
          Portfolio.Position("FIGI1", "AAPL", "Apple Inc.", BigDecimal(100)),
          Portfolio.Position("FIGI2", "GOOGL", "Alphabet Inc.", BigDecimal(50))
        )
        for {
          tgGateway <- Fakes.tgGateway()
          tcsFacade <- Fakes.tcsFacade(Portfolio(positions))
          handler = new InfoHandler[IO](tgGateway, tcsFacade)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Apple Inc. - 100")
          msg must contain("Alphabet Inc. - 50")
          msg.split("\n") must have size 2
        }
      }

      "truncates decimal balance to integer" >> {
        val positions =
          List(Portfolio.Position("FIGI1", "AAPL", "Apple Inc.", BigDecimal("100.789")))
        for {
          tgGateway <- Fakes.tgGateway()
          tcsFacade <- Fakes.tcsFacade(Portfolio(positions))
          handler = new InfoHandler[IO](tgGateway, tcsFacade)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "Apple Inc. - 100"
      }

      "sends an empty message for an empty portfolio" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          tcsFacade <- Fakes.tcsFacade(Portfolio(List.empty))
          handler = new InfoHandler[IO](tgGateway, tcsFacade)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== ""
      }
    }
  }
}
