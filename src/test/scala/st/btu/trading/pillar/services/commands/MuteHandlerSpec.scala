package st.btu.trading.pillar.services.commands

import cats.effect.{Clock, IO}
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AlertFakes, Fakes}

import java.time.Instant

class MuteHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)

  private def isRoughlyMinutesAhead(before: Instant, minutes: Int, until: Instant): Boolean = {
    val target = before.plusSeconds(minutes.toLong * 60)
    !until.isBefore(target.minusSeconds(30)) && !until.isAfter(target.plusSeconds(30))
  }

  "MuteHandler" >> {
    "handle" >> {

      "mutes the dispatcher for the default 60 minutes when no argument is given" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          dispatcher <- AlertFakes.alertDispatcher()
          handler = new MuteHandler[IO](tgGateway, dispatcher)
          before <- Clock[IO].realTimeInstant
          _ <- handler.handle(chatId, Nil)
          muteUntil <- dispatcher.muteUntil
        } yield muteUntil must beSome[Instant].like { case until =>
          isRoughlyMinutesAhead(before, 60, until) must beTrue
        }
      }

      "mutes for the number of minutes given as an argument" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          dispatcher <- AlertFakes.alertDispatcher()
          handler = new MuteHandler[IO](tgGateway, dispatcher)
          before <- Clock[IO].realTimeInstant
          _ <- handler.handle(chatId, List("5"))
          muteUntil <- dispatcher.muteUntil
        } yield muteUntil must beSome[Instant].like { case until =>
          isRoughlyMinutesAhead(before, 5, until) must beTrue
        }
      }

      "ignores a non-numeric argument and falls back to the default" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          dispatcher <- AlertFakes.alertDispatcher()
          handler = new MuteHandler[IO](tgGateway, dispatcher)
          before <- Clock[IO].realTimeInstant
          _ <- handler.handle(chatId, List("forever"))
          muteUntil <- dispatcher.muteUntil
        } yield muteUntil must beSome[Instant].like { case until =>
          isRoughlyMinutesAhead(before, 60, until) must beTrue
        }
      }

      "replies with a confirmation message naming the duration" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          dispatcher <- AlertFakes.alertDispatcher()
          handler = new MuteHandler[IO](tgGateway, dispatcher)
          _ <- handler.handle(chatId, List("15"))
          messages <- tgGateway.sentMessages
        } yield messages.map(_.message) must contain(exactly("Alerts muted for 15 minute(s)."))
      }
    }
  }
}
