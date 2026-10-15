package st.btu.trading.pillar.alerts

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.gateways.MarketDataSource
import st.btu.trading.pillar.models.domain.{AccountId, Currency}
import st.btu.trading.pillar.models.market.{MarketDataError, Ticker}
import st.btu.trading.pillar.paper.*
import st.btu.trading.pillar.strategy.*
import st.btu.trading.pillar.testkit.{AlertFakes, MarketDataFakes, PaperFakes, StrategyFakes}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}

import java.time.{ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class AlertProcessImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val now = ZonedDateTime.now(ZoneOffset.UTC)
  private val fastConfig =
    AlertProcessConfig(pollInterval = 10.millis, rateLimitBackoff = 10.millis)

  private def buildProcess(
    bindings: List[StrategyBinding],
    rules: List[PriceCrossed],
    registry: StrategyRegistry[IO],
    liveStrategyService: LiveStrategyService[IO],
    marketDataSource: MarketDataSource[IO],
    dispatcher: AlertDispatcher[IO],
    paperBroker: PaperBroker[IO]
  ): IO[AlertProcessImpl[IO]] =
    MarketDataFakes.withLocal(ProcessContext("init", accountId)).flatMap { wl =>
      implicit val withLocal: WithLocal[IO, ProcessContext] = wl
      implicit val genUUID: GenUUID[IO] = GenUUID.syncGenUUID[IO]
      implicit val logs: Logs[IO, IO] =
        Logs.const[IO, IO](Logging.Make.plain[IO].forService[AlertProcess[IO]])
      for {
        bindingStorage <- AlertFakes.strategyBindingStorage(bindings)
        ruleStorage <- AlertFakes.alertRuleStorage(rules)
        priceHistory <- MarketDataFakes.priceHistoryStorage()
        sources <- MarketDataFakes.marketDataSources(yahoo = Some(marketDataSource))
        process <- AlertProcessImpl.create[IO, IO](
          bindingStorage,
          ruleStorage,
          registry,
          liveStrategyService,
          sources,
          priceHistory,
          dispatcher,
          paperBroker,
          SystemAlerts.noop[IO],
          fastConfig
        )
      } yield process
    }

  private def noopPaperBroker: IO[PaperBroker[IO]] =
    PaperFakes.paperStorage().map(PaperBrokerImpl.create[IO](_, PaperBrokerConfig(0, 0)))

  "AlertProcessImpl" >> {
    "continues past a binding whose strategy isn't registered" >> {
      implicit val registryLogs: Logs[IO, IO] =
        Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])

      val knownStrategy = StrategyFakes.holdStrategy(StrategyId("known"))
      val goodBinding =
        AlertFakes.strategyBinding(id = 1, strategyId = knownStrategy.id, ticker = Ticker("MSFT"))
      val badBinding =
        AlertFakes.strategyBinding(
          id = 2,
          strategyId = StrategyId("missing"),
          ticker = Ticker("IBM")
        )

      val bindingAlert =
        Alert(
          goodBinding.ruleKey,
          Some(goodBinding.ticker),
          AlertKind.StrategyBuy,
          "t",
          "b",
          None,
          now
        )

      for {
        registry <- StrategyRegistryImpl.create[IO, IO]
        _ <- registry.register(knownStrategy)
        liveService <- AlertFakes.liveStrategyService(
          Map(goodBinding.ruleKey -> IO.pure(LiveEvaluation(List(bindingAlert), List.empty)))
        )
        marketData <- MarketDataFakes.marketDataSource(quotes =
          Map(Ticker("MSFT") -> IO.pure(Some(AlertFakes.quote(Ticker("MSFT"), 99, now))))
        )
        dispatcher <- AlertFakes.alertDispatcher()
        paperBroker <- noopPaperBroker
        process <- buildProcess(
          List(goodBinding, badBinding),
          List.empty,
          registry,
          liveService,
          marketData,
          dispatcher,
          paperBroker
        )
        _ <- process.run.take(1).compile.drain
        dispatched <- dispatcher.dispatched
        evaluatedKeys <- liveService.evaluatedRuleKeys
      } yield {
        evaluatedKeys must contain(goodBinding.ruleKey)
        evaluatedKeys.contains(badBinding.ruleKey) must beFalse
        dispatched.map(_.ruleKey) must contain(goodBinding.ruleKey)
      }
    }

    "continues past a rule whose quote lookup is rate-limited" >> {
      implicit val registryLogs: Logs[IO, IO] =
        Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])

      // No proximity needed to detect a cross: a generous Percent band fires a PreTarget on the
      // very first evaluation (no previous quote required), which is enough to prove the good
      // rule's alert reaches the dispatcher despite the bad rule failing.
      val goodRule = AlertFakes.priceCrossed(
        id = 1,
        ticker = Ticker("AAPL"),
        threshold = 100,
        proximity = Some(Proximity.Percent(50))
      )
      val badRule = AlertFakes.priceCrossed(id = 2, ticker = Ticker("BAD"))

      for {
        registry <- StrategyRegistryImpl.create[IO, IO]
        liveService <- AlertFakes.liveStrategyService()
        marketData <- MarketDataFakes.marketDataSource(quotes =
          Map(
            Ticker("AAPL") -> IO.pure(Some(AlertFakes.quote(Ticker("AAPL"), 99, now))),
            Ticker("BAD") -> IO.raiseError(MarketDataError.RateLimited(Some(5.millis)))
          )
        )
        dispatcher <- AlertFakes.alertDispatcher()
        paperBroker <- noopPaperBroker
        process <- buildProcess(
          List.empty,
          List(goodRule, badRule),
          registry,
          liveService,
          marketData,
          dispatcher,
          paperBroker
        )
        _ <- process.run.take(1).compile.drain
        dispatched <- dispatcher.dispatched
      } yield {
        dispatched.map(_.ruleKey) must contain(goodRule.ruleKey)
        dispatched.exists(_.ruleKey == badRule.ruleKey) must beFalse
      }
    }

    "executes a paper-mode binding's Buy/Sell signals, and never a signal-mode binding's" >> {
      implicit val registryLogs: Logs[IO, IO] =
        Logs.const[IO, IO](Logging.Make.plain[IO].forService[StrategyRegistry[IO]])

      // Both bindings default to the same strategyId (AlertFakes.strategyBinding's default), so
      // registering one strategy covers both - only `mode` differs between them.
      val strategy = StrategyFakes.holdStrategy(StrategyId("test-strategy"))
      val paperBinding =
        AlertFakes.strategyBinding(id = 1, ticker = Ticker("AAPL"), mode = BindingMode.Paper)
      val signalBinding =
        AlertFakes.strategyBinding(id = 2, ticker = Ticker("MSFT"), mode = BindingMode.Signal)

      val paperBuy = StrategySignal.Buy(Ticker("AAPL"), Sizing.Quantity(1), "paper buy")
      // Scripted onto the signal-mode binding to prove it is never handed to PaperBroker, even
      // though LiveStrategyService reports it as a real Buy/Sell signal.
      val wouldBeBuy = StrategySignal.Buy(Ticker("MSFT"), Sizing.Quantity(1), "should not execute")

      for {
        registry <- StrategyRegistryImpl.create[IO, IO]
        _ <- registry.register(strategy)
        liveService <- AlertFakes.liveStrategyService(
          Map(
            paperBinding.ruleKey -> IO.pure(LiveEvaluation(List.empty, List(paperBuy))),
            signalBinding.ruleKey -> IO.pure(LiveEvaluation(List.empty, List(wouldBeBuy)))
          )
        )
        marketData <- MarketDataFakes.marketDataSource(quotes =
          Map(
            Ticker("AAPL") -> IO.pure(Some(AlertFakes.quote(Ticker("AAPL"), 100, now))),
            Ticker("MSFT") -> IO.pure(Some(AlertFakes.quote(Ticker("MSFT"), 50, now)))
          )
        )
        dispatcher <- AlertFakes.alertDispatcher()
        paperStorage <- PaperFakes.paperStorage(accounts =
          List(PaperAccount(accountId, 10000, Currency("USD")))
        )
        paperBroker = PaperBrokerImpl.create[IO](paperStorage, PaperBrokerConfig(0, 0))
        process <- buildProcess(
          List(paperBinding, signalBinding),
          List.empty,
          registry,
          liveService,
          marketData,
          dispatcher,
          paperBroker
        )
        _ <- process.run.take(1).compile.drain
        orders <- paperStorage.allOrders
      } yield {
        orders.map(_.ticker) must_== List(Ticker("AAPL"))
        orders.head.status must_== PaperOrderStatus.Filled
        orders.head.quantity must_== 1L
      }
    }
  }
}
