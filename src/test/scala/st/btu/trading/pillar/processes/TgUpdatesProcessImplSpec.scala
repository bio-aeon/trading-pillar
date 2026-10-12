package st.btu.trading.pillar.processes

import cats.effect.{IO, Ref}
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg.*
import st.btu.trading.pillar.services.CommandHandleService
import st.btu.trading.pillar.services.commands.ParsedCommand
import st.btu.trading.pillar.testkit.Fakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.Logging

class TgUpdatesProcessImplSpec extends Specification with CatsEffect {

  private implicit val logging: Logging[IO] =
    Logging.Make.plain[IO].forService[TgUpdatesProcess[IO]]

  private val chatId = ChatId(42L)

  private def callbackUpdate(
    data: Option[String],
    message: Option[Message] = Some(Message(1L, Chat(chatId), None))
  ): Update =
    Update(1L, message = None, callback_query = Some(CallbackQuery("cb1", None, message, data)))

  private def messageUpdate(text: String): Update =
    Update(1L, message = Some(Message(1L, Chat(chatId), Some(text))), callback_query = None)

  // Records every call instead of actually dispatching to a CommandHandler - this spec is about
  // routing (which chat/command reaches the service, and whether the callback was answered),
  // not about any individual handler's own behaviour.
  private def recordingService: IO[(CommandHandleService[IO], IO[List[(ChatId, ParsedCommand)]])] =
    Ref.of[IO, List[(ChatId, ParsedCommand)]](List.empty).map { ref =>
      val service = new CommandHandleService[IO] {
        def handleCommand(chatId: ChatId, parsed: ParsedCommand): IO[Unit] =
          ref.update(_ :+ (chatId -> parsed))
      }
      (service, ref.get)
    }

  private def buildGateway(update: Update): IO[Fakes.FakeTgGateway[IO]] =
    Fakes.tgGateway(updatesResponse = Result(ok = true, result = List(update)))

  "TgUpdatesProcessImpl" >> {
    "run" >> {

      "routes a callback whose data starts with / as command text for the callback message's chat, answering the callback first" >> {
        for {
          (service, calls) <- recordingService
          tgGateway <- buildGateway(callbackUpdate(Some("/ping")))
          process = new TgUpdatesProcessImpl[IO](tgGateway, service)
          _ <- process.run.take(1).compile.drain
          routed <- calls
          answered <- tgGateway.answeredCallbacks
        } yield {
          routed must_== List(chatId -> ParsedCommand(Command.Ping, Nil))
          answered must_== List("cb1")
        }
      }

      "neither routes nor answers a callback whose data doesn't look like a command" >> {
        for {
          (service, calls) <- recordingService
          tgGateway <- buildGateway(callbackUpdate(Some("hello")))
          process = new TgUpdatesProcessImpl[IO](tgGateway, service)
          _ <- process.run.take(1).compile.drain
          routed <- calls
          answered <- tgGateway.answeredCallbacks
        } yield {
          routed must beEmpty
          answered must beEmpty
        }
      }

      "answers a command-shaped callback even when it carries no message, but routes nothing" >> {
        for {
          (service, calls) <- recordingService
          tgGateway <- buildGateway(callbackUpdate(Some("/ping"), message = None))
          process = new TgUpdatesProcessImpl[IO](tgGateway, service)
          _ <- process.run.take(1).compile.drain
          routed <- calls
          answered <- tgGateway.answeredCallbacks
        } yield {
          routed must beEmpty
          answered must_== List("cb1")
        }
      }

      "still routes a plain message update by its own chat and text" >> {
        for {
          (service, calls) <- recordingService
          tgGateway <- buildGateway(messageUpdate("/ping"))
          process = new TgUpdatesProcessImpl[IO](tgGateway, service)
          _ <- process.run.take(1).compile.drain
          routed <- calls
          answered <- tgGateway.answeredCallbacks
        } yield {
          routed must_== List(chatId -> ParsedCommand(Command.Ping, Nil))
          answered must beEmpty
        }
      }
    }
  }
}
