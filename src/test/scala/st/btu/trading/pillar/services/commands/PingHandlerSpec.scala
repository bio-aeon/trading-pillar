package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.Fakes

class PingHandlerSpec extends Specification with CatsEffect {

  "PingHandler" >> {
    "handle" >> {

      "includes a formatted timestamp in the reply" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          handler = new PingHandler[IO](tgGateway)
          _ <- handler.handle(ChatId(12345L), Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must startWith("Working normally. Time is:")
          msg must beMatching(".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")
        }
      }

      "replies to the chat the command came from" >> {
        val chatId = ChatId(99999L)
        for {
          tgGateway <- Fakes.tgGateway()
          handler = new PingHandler[IO](tgGateway)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.chatId must_== chatId
      }
    }
  }
}
