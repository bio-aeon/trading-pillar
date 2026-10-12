package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.strategy.{StrategyId, StrategyRegistry, StrategyRegistryImpl}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{Fakes, StrategyFakes}
import tofu.logging.{Logging, Logs}

class StrategiesHandlerSpec extends Specification with CatsEffect {

  private def buildRegistry: IO[StrategyRegistry[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])
    StrategyRegistryImpl.create[IO, IO]
  }

  "StrategiesHandler" >> {
    "handle" >> {

      "reports when no strategies are registered" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          handler = new StrategiesHandler[IO](tgGateway, registry)
          _ <- handler.handle(ChatId(1L), Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No strategies registered."
      }

      "lists id, name and parameters for each registered strategy" >> {
        val strategy = StrategyFakes.ScriptedStrategy(
          StrategyId("ma-cross-50-200"),
          warmupBars = 200,
          onBarFn = (_, _) => Nil,
          name = "Moving Average Crossover",
          parameters = Map("fast" -> "50", "slow" -> "200")
        )
        for {
          tgGateway <- Fakes.tgGateway()
          registry <- buildRegistry
          _ <- registry.register(strategy)
          handler = new StrategiesHandler[IO](tgGateway, registry)
          _ <- handler.handle(ChatId(1L), Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("ma-cross-50-200")
          msg must contain("Moving Average Crossover")
          msg must contain("fast=50")
          msg must contain("slow=200")
        }
      }
    }
  }
}
