package st.btu.trading.pillar.services

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.services.commands.{CommandHandler, ParsedCommand}
import st.btu.trading.pillar.testkit.Fakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

class CommandHandleServiceSpec extends Specification with CatsEffect {

  private val testChatId: ChatId = ChatId(12345L)

  private implicit val logging: Logging[IO] =
    Logging.Make.plain[IO].forService[CommandHandleService[IO]]
  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[CommandHandleService[IO]])

  private def okHandler(cmd: Command, tgGateway: Fakes.FakeTgGateway[IO]): CommandHandler[IO] =
    new CommandHandler[IO] {
      def command: Command = cmd
      def usage: String = s"/${cmd.value}"
      def handle(chatId: ChatId, args: List[String]): IO[Unit] =
        tgGateway.sendMessage(chatId, s"ok:${args.mkString(",")}")
    }

  private def failingHandler(cmd: Command): CommandHandler[IO] =
    new CommandHandler[IO] {
      def command: Command = cmd
      def usage: String = s"/${cmd.value}"
      def handle(chatId: ChatId, args: List[String]): IO[Unit] =
        IO.raiseError(new RuntimeException("boom"))
    }

  "CommandHandleServiceImpl" >> {
    "handleCommand" >> {

      "routes a parsed command to its matching handler, passing the arguments through" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          service = new CommandHandleServiceImpl[IO](
            tgGateway,
            List(okHandler(Command.Ping, tgGateway))
          )
          _ <- service.handleCommand(testChatId, ParsedCommand(Command.Ping, List("a", "b")))
          messages <- tgGateway.sentMessages
        } yield messages.map(_.message) must contain(exactly("ok:a,b"))
      }

      "replies 'Unknown command. Try /help' for an explicitly unknown command" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          service = new CommandHandleServiceImpl[IO](tgGateway, Nil)
          _ <- service.handleCommand(testChatId, ParsedCommand(Command.Unknown, Nil))
          messages <- tgGateway.sentMessages
        } yield messages.map(_.message) must contain(exactly("Unknown command. Try /help"))
      }

      "replies 'Unknown command. Try /help' for a known command with no registered handler" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          service = new CommandHandleServiceImpl[IO](tgGateway, Nil)
          _ <- service.handleCommand(testChatId, ParsedCommand(Command.Watchlist, Nil))
          messages <- tgGateway.sentMessages
        } yield messages.map(_.message) must contain(exactly("Unknown command. Try /help"))
      }

      "replies with a failure message and swallows the error when a handler fails" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          service =
            new CommandHandleServiceImpl[IO](tgGateway, List(failingHandler(Command.Ping)))
          result <- service.handleCommand(testChatId, ParsedCommand(Command.Ping, Nil)).attempt
          messages <- tgGateway.sentMessages
        } yield {
          result must beRight(())
          messages.map(_.message) must contain(exactly("Command failed: boom"))
        }
      }

      "sends the reply to the chat the update came from" >> {
        val specificChatId = ChatId(99999L)
        for {
          tgGateway <- Fakes.tgGateway()
          service = new CommandHandleServiceImpl[IO](tgGateway, Nil)
          _ <- service.handleCommand(specificChatId, ParsedCommand(Command.Unknown, Nil))
          messages <- tgGateway.sentMessages
        } yield messages.head.chatId must_== specificChatId
      }
    }
  }
}
