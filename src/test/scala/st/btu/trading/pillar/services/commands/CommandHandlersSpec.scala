package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.Fakes

class CommandHandlersSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)

  private def extraHandler(handled: Command, usageText: String): CommandHandler[IO] =
    new CommandHandler[IO] {
      def command: Command = handled
      def usage: String = usageText
      def handle(chatId: ChatId, args: List[String]): IO[Unit] = IO.unit
    }

  private val extras = List(
    extraHandler(Command.Watchlist, "/watchlist - the watchlist"),
    extraHandler(Command.Strategies, "/strategies - the strategies")
  )

  "CommandHandlers" >> {
    "standard" >> {

      "serves ping, info, the extra handlers and help, in that order" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          tcsFacade <- Fakes.tcsFacade()
        } yield CommandHandlers
          .standard[IO](tgGateway, tcsFacade, extras)
          .map(_.command) must_== List(
          Command.Ping,
          Command.Info,
          Command.Watchlist,
          Command.Strategies,
          Command.Help
        )
      }

      "serves only ping, info and help when there are no extra handlers" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          tcsFacade <- Fakes.tcsFacade()
        } yield CommandHandlers
          .standard[IO](tgGateway, tcsFacade, Nil)
          .map(_.command) must_== List(Command.Ping, Command.Info, Command.Help)
      }

      "lets help list the usage of every handler before it, in order" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          tcsFacade <- Fakes.tcsFacade()
          help = CommandHandlers.standard[IO](tgGateway, tcsFacade, extras).last
          _ <- help.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.map(_.message) must_== List("""/ping - health check
            |/info - portfolio positions
            |/watchlist - the watchlist
            |/strategies - the strategies
            |/help - list available commands""".stripMargin)
      }
    }
  }
}
