package st.btu.trading.pillar.services.commands

import cats.effect.{Deferred, IO}
import cats.effect.std.CountDownLatch
import cats.effect.testing.specs2.CatsEffect
import cats.syntax.parallel.*
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.{CapturedReply, CapturingTgGateway}
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

class CommandRunnerSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[CommandRunner[IO]])

  private def handler(
    handled: Command
  )(reply: (ChatId, List[String]) => IO[Unit]): CommandHandler[IO] =
    new CommandHandler[IO] {
      def command: Command = handled
      def usage: String = s"/${handled.value}"
      def handle(chatId: ChatId, args: List[String]): IO[Unit] = reply(chatId, args)
    }

  private def runnerOver(
    handlers: CapturingTgGateway[IO] => List[CommandHandler[IO]]
  ): IO[(CommandRunner[IO], CapturingTgGateway[IO])] =
    for {
      gateway <- CapturingTgGateway.create[IO, IO]
      runner <- CommandRunnerImpl.create[IO, IO](gateway, handlers(gateway))
    } yield (runner, gateway)

  private def echoing(gateway: CapturingTgGateway[IO]): List[CommandHandler[IO]] =
    List(
      handler(Command.Ping)((chatId, args) =>
        gateway.sendMessage(chatId, s"pong:${args.mkString(",")}")
      )
    )

  "CommandRunner" >> {
    "run" >> {

      "returns what the command's handler replied" >> {
        for {
          built <- runnerOver(echoing)
          result <- built._1.run("/ping a b")
        } yield result must beRight(List[CapturedReply](CapturedReply.Text("pong:a,b")))
      }

      "returns every reply of a handler in the order it sent them" >> {
        for {
          built <- runnerOver(gateway =>
            List(
              handler(Command.Ping)((chatId, _) =>
                gateway.sendMessage(chatId, "one") >> gateway.sendMessage(chatId, "two")
              )
            )
          )
          result <- built._1.run("/ping")
        } yield result must beRight(
          List[CapturedReply](CapturedReply.Text("one"), CapturedReply.Text("two"))
        )
      }

      "ignores whitespace around the line" >> {
        for {
          built <- runnerOver(echoing)
          result <- built._1.run("  /ping x \n")
        } yield result must beRight(List[CapturedReply](CapturedReply.Text("pong:x")))
      }

      "keeps the replies of runs in flight at the same time apart" >> {
        for {
          bothRunning <- CountDownLatch[IO](2)
          built <- runnerOver(gateway =>
            List(
              handler(Command.Ping)((chatId, args) =>
                gateway.sendMessage(chatId, s"start ${args.head}") >>
                  bothRunning.release >> bothRunning.await >>
                  gateway.sendMessage(chatId, s"end ${args.head}")
              )
            )
          )
          results <- (built._1.run("/ping one"), built._1.run("/ping two")).parTupled
        } yield results must_== ((
          Right(List(CapturedReply.Text("start one"), CapturedReply.Text("end one"))),
          Right(List(CapturedReply.Text("start two"), CapturedReply.Text("end two")))
        ))
      }

      "answers a command nobody handles with the unknown command reply" >> {
        for {
          built <- runnerOver(echoing)
          result <- built._1.run("/nonsense")
        } yield result must beRight(
          List[CapturedReply](CapturedReply.Text("Unknown command. Try /help"))
        )
      }

      "answers a handler that fails with the failure reply" >> {
        for {
          built <- runnerOver(_ =>
            List(handler(Command.Ping)((_, _) => IO.raiseError(new RuntimeException("boom"))))
          )
          result <- built._1.run("/ping")
        } yield result must beRight(List[CapturedReply](CapturedReply.Text("Command failed: boom")))
      }

      "refuses a line that is not a command" >> {
        for {
          built <- runnerOver(echoing)
          result <- built._1.run("ping")
        } yield result must beLeft[CommandRunError](CommandRunError.NotACommand("ping"))
      }

      "drops the replies of a run that is cancelled" >> {
        for {
          started <- CountDownLatch[IO](1)
          chatOfRun <- Deferred[IO, ChatId]
          built <- runnerOver(gateway =>
            List(
              handler(Command.Ping)((chatId, _) =>
                chatOfRun.complete(chatId) >> gateway.sendMessage(chatId, "partial") >>
                  started.release >> IO.never
              )
            )
          )
          (runner, gateway) = built
          fiber <- runner.run("/ping").start
          _ <- started.await
          _ <- fiber.cancel
          chatId <- chatOfRun.get
          leftOver <- gateway.take(chatId)
        } yield leftOver must beEmpty
      }
    }

    "parse" >> {

      "reads the command and arguments of a command line" >> {
        CommandRunner.parse("/watchlist add AAPL") must beRight(
          ParsedCommand(Command.Watchlist, List("add", "AAPL"))
        )
      }

      "refuses a line that does not start with a slash" >> {
        CommandRunner.parse("watchlist") must beLeft[CommandRunError](
          CommandRunError.NotACommand("watchlist")
        )
      }
    }
  }
}
