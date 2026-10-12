package st.btu.trading.pillar.strategy

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

class StrategyRegistrySpec extends Specification with CatsEffect {

  private def strategy(idValue: String, warmup: Int = 1): Strategy =
    st.btu.trading.pillar.testkit.StrategyFakes.holdStrategy(StrategyId(idValue), warmup)

  private def buildRegistry: IO[StrategyRegistry[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])
    StrategyRegistryImpl.create[IO, IO]
  }

  "StrategyRegistryImpl" >> {
    "returns nothing for an id that was never registered" >>
      buildRegistry.flatMap(_.get(StrategyId("missing"))).map(_ must beNone)

    "registers and retrieves a strategy by id" >> {
      val s = strategy("a")
      for {
        registry <- buildRegistry
        _ <- registry.register(s)
        found <- registry.get(s.id)
      } yield found must beSome(s)
    }

    "lists all registered strategies" >> {
      val a = strategy("a")
      val b = strategy("b")
      for {
        registry <- buildRegistry
        _ <- registry.register(a)
        _ <- registry.register(b)
        listed <- registry.list
      } yield listed must containTheSameElementsAs(List(a, b))
    }

    "replaces a strategy registered under a duplicate id" >> {
      val original = strategy("a", warmup = 1)
      val replacement = strategy("a", warmup = 2)
      for {
        registry <- buildRegistry
        _ <- registry.register(original)
        _ <- registry.register(replacement)
        found <- registry.get(StrategyId("a"))
        listed <- registry.list
      } yield {
        found must beSome(replacement)
        listed must haveSize(1)
      }
    }
  }
}
