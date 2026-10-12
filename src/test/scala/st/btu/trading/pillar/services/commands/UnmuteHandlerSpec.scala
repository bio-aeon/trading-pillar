package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AlertFakes, Fakes}

class UnmuteHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)

  "UnmuteHandler" >> {
    "handle" >> {
      "unmutes the dispatcher and confirms" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          dispatcher <- AlertFakes.alertDispatcher()
          _ <- dispatcher.mute(java.time.Instant.now.plusSeconds(3600))
          handler = new UnmuteHandler[IO](tgGateway, dispatcher)
          _ <- handler.handle(chatId, Nil)
          muteUntil <- dispatcher.muteUntil
          unmuteCalls <- dispatcher.unmuteCalls
          messages <- tgGateway.sentMessages
        } yield {
          muteUntil must beNone
          unmuteCalls must_== 1
          messages.map(_.message) must contain(exactly("Alerts unmuted."))
        }
      }
    }
  }
}
