package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.Fakes

class HelpHandlerSpec extends Specification with CatsEffect {

  "HelpHandler" >> {
    "handle" >> {
      "replies with every usage line, one per command" >> {
        val usages = List("/ping - health check", "/info - portfolio positions")
        for {
          tgGateway <- Fakes.tgGateway()
          handler = new HelpHandler[IO](tgGateway, usages)
          _ <- handler.handle(ChatId(1L), Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "/ping - health check\n/info - portfolio positions"
      }
    }
  }
}
