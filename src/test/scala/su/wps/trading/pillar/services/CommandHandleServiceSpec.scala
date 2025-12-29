package su.wps.trading.pillar.services

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import su.wps.trading.pillar.models.domain.{Command, Portfolio}
import su.wps.trading.pillar.models.tg.ChatId
import su.wps.trading.pillar.testkit.Fakes

class CommandHandleServiceSpec extends Specification with CatsEffect {

  val testChatId: ChatId = ChatId(12345L)

  "CommandHandleServiceImpl" should {
    "send 'Unknown command.' for Unknown command" in {
      for {
        tgGateway <- Fakes.tgGateway
        tcsFacade <- Fakes.tcsFacade()
        service = new CommandHandleServiceImpl[IO](tgGateway, tcsFacade)
        _ <- service.handleCommand(testChatId, Command.Unknown)
        messages <- tgGateway.sentMessages
      } yield messages.map(_.message) must contain(exactly("Unknown command."))
    }

    "format portfolio positions as 'name - balance' for Info command" in {
      val positions = List(
        Portfolio.Position("FIGI1", "AAPL", "Apple Inc.", BigDecimal(100)),
        Portfolio.Position("FIGI2", "GOOGL", "Alphabet Inc.", BigDecimal(50))
      )

      for {
        tgGateway <- Fakes.tgGateway
        tcsFacade <- Fakes.tcsFacade(Portfolio(positions))
        service = new CommandHandleServiceImpl[IO](tgGateway, tcsFacade)
        _ <- service.handleCommand(testChatId, Command.Info)
        messages <- tgGateway.sentMessages
      } yield {
        val msg = messages.head.message
        msg must contain("Apple Inc. - 100")
        msg must contain("Alphabet Inc. - 50")
        msg.split("\n") must have size 2
      }
    }

    "truncate decimal balance to integer" in {
      val positions = List(Portfolio.Position("FIGI1", "AAPL", "Apple Inc.", BigDecimal("100.789")))

      for {
        tgGateway <- Fakes.tgGateway
        tcsFacade <- Fakes.tcsFacade(Portfolio(positions))
        service = new CommandHandleServiceImpl[IO](tgGateway, tcsFacade)
        _ <- service.handleCommand(testChatId, Command.Info)
        messages <- tgGateway.sentMessages
      } yield messages.head.message must_== "Apple Inc. - 100"
    }

    "include formatted timestamp in Ping response" in {
      for {
        tgGateway <- Fakes.tgGateway
        tcsFacade <- Fakes.tcsFacade()
        service = new CommandHandleServiceImpl[IO](tgGateway, tcsFacade)
        _ <- service.handleCommand(testChatId, Command.Ping)
        messages <- tgGateway.sentMessages
      } yield {
        val msg = messages.head.message
        msg must startWith("Working normally. Time is:")
        msg must beMatching(".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")
      }
    }

    "send message to the specified chat" in {
      val specificChatId = ChatId(99999L)

      for {
        tgGateway <- Fakes.tgGateway
        tcsFacade <- Fakes.tcsFacade()
        service = new CommandHandleServiceImpl[IO](tgGateway, tcsFacade)
        _ <- service.handleCommand(specificChatId, Command.Unknown)
        messages <- tgGateway.sentMessages
      } yield messages.head.chatId must_== specificChatId
    }

    "send empty string for empty portfolio" in {
      for {
        tgGateway <- Fakes.tgGateway
        tcsFacade <- Fakes.tcsFacade(Portfolio(List.empty))
        service = new CommandHandleServiceImpl[IO](tgGateway, tcsFacade)
        _ <- service.handleCommand(testChatId, Command.Info)
        messages <- tgGateway.sentMessages
      } yield messages.head.message must_== ""
    }
  }
}
