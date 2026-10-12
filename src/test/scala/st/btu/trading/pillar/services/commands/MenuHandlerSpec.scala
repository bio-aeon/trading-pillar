package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.tg.{ChatId, InlineButton}
import st.btu.trading.pillar.testkit.Fakes

class MenuHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)

  "MenuHandler" >> {
    "handle" >> {

      "sends an inline keyboard whose buttons' callback data is the command text itself" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          handler = new MenuHandler[IO](tgGateway)
          _ <- handler.handle(chatId, Nil)
          keyboards <- tgGateway.sentKeyboards
          messages <- tgGateway.sentMessages
        } yield {
          messages must beEmpty
          keyboards must haveSize(1)
          val keyboard = keyboards.head
          keyboard.chatId must_== chatId
          // Every button's callback data round-trips as the literal command text.
          keyboard.rows.flatten must contain(InlineButton("/portfolio", "/portfolio"))
          keyboard.rows.flatten must contain(InlineButton("/paper", "/paper"))
          keyboard.rows.flatten.map(_.callbackData) must_== keyboard.rows.flatten.map(_.text)
          // Every advertised command is present exactly once, and rows have at most 2 buttons.
          keyboard.rows.flatten.map(_.text) must_== List(
            "/portfolio",
            "/positions",
            "/performance",
            "/alerts",
            "/watchlist",
            "/dividends",
            "/paper"
          )
          keyboard.rows.forall(_.size <= 2) must beTrue
        }
      }
    }
  }
}
