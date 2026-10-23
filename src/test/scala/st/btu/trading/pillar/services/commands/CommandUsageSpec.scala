package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.strategy.StrategyRegistryImpl
import st.btu.trading.pillar.strategy.backtest.BacktestConfig
import st.btu.trading.pillar.testkit.*
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

class CommandUsageSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)
  private val alertChannels = List("telegram")
  private val backtestDefaults = BacktestConfig(10000, BigDecimal("0.001"), 5)

  private implicit val logging: Logging[IO] = Logging.Make.plain[IO].forService[CommandUsageSpec]
  private implicit val logs: Logs[IO, IO] = Logs.const[IO, IO](logging)

  private def allHandlers: IO[List[CommandHandler[IO]]] =
    for {
      tgGateway <- Fakes.tgGateway()
      tcsFacade <- Fakes.tcsFacade()
      watchlist <- MarketDataFakes.watchlistStorage()
      tickerSources <- MarketDataFakes.tickerSourceStorage()
      sources <- MarketDataFakes.marketDataSources()
      priceHistory <- MarketDataFakes.priceHistoryStorage()
      dividends <- MarketDataFakes.dividendStorage()
      registry <- StrategyRegistryImpl.create[IO, IO]
      backtests <- StrategyFakes.backtestService(_ => IO.raiseError(new RuntimeException("unused")))
      alertHistory <- AlertFakes.alertHistoryStorage()
      alertRules <- AlertFakes.alertRuleStorage()
      alertDispatcher <- AlertFakes.alertDispatcher()
      valuationStorage <- AnalyticsFakes.portfolioValuationStorage()
      valuationService <- AnalyticsFakes.portfolioValuationService()
      targets <- AnalyticsFakes.allocationTargetStorage()
      operations <- AnalyticsFakes.tcsPortfolioOperationStorage()
      analysis <- AnalysisFakes.analysisService()
      paper <- PaperFakes.paperStorage()
      news <- NewsFakes.newsSource()
      screener <- ScreenerFakes.screener()
      taxService <- TaxFakes.taxService()
    } yield CommandHandlers.standard[IO](
      tgGateway,
      tcsFacade,
      List(
        new WatchlistHandler[IO](tgGateway, watchlist, tickerSources, sources, accountId),
        new SourceHandler[IO](tgGateway, tickerSources, sources),
        new StrategiesHandler[IO](tgGateway, registry),
        new BacktestHandler[IO](tgGateway, registry, backtests, sources, backtestDefaults),
        new SweepHandler[IO](tgGateway, registry, priceHistory, sources, backtestDefaults),
        new AlertsHandler[IO](tgGateway, alertHistory),
        new AlertRuleHandler[IO](tgGateway, alertRules, accountId, alertChannels),
        new MuteHandler[IO](tgGateway, alertDispatcher),
        new UnmuteHandler[IO](tgGateway, alertDispatcher),
        new DividendsHandler[IO](tgGateway, dividends, watchlist, sources, accountId),
        new PerformanceHandler[IO](tgGateway, valuationStorage, accountId),
        new PortfolioHandler[IO](tgGateway, valuationStorage, accountId),
        new PositionsHandler[IO](tgGateway, valuationService, accountId),
        new AllocationHandler[IO](tgGateway, valuationService, accountId),
        new DriftHandler[IO](tgGateway, valuationService, targets, accountId),
        new RebalanceHandler[IO](tgGateway, valuationService, targets, accountId),
        new PaperHandler[IO](tgGateway, paper, sources, accountId),
        new NewsHandler[IO](tgGateway, news),
        new ScreenHandler[IO](tgGateway, screener),
        new TaxHandler[IO](tgGateway, taxService, accountId),
        new LotsHandler[IO](tgGateway, taxService, accountId),
        new ExportHandler[IO](
          tgGateway,
          operations,
          valuationStorage,
          dividends,
          watchlist,
          sources,
          accountId
        ),
        new AnalyzeHandler[IO](tgGateway, analysis),
        new CompareHandler[IO](tgGateway, analysis),
        new MenuHandler[IO](tgGateway)
      )
    )

  private def handlerOf(command: Command): IO[CommandHandler[IO]] =
    allHandlers.flatMap(all =>
      IO.fromOption(all.find(_.command == command))(
        new NoSuchElementException(s"no handler is assembled for /${command.value}")
      )
    )

  "Command handlers" >> {
    "usage" >> {
      Fragments.foreach(Command.values.filterNot(_ == Command.Unknown)) { command =>
        s"/${command.value} reaches the chat as written" >> {
          handlerOf(command).map(handler =>
            CommandReply.prepare(handler.usage) must_== handler.usage
          )
        }
      }
    }
  }
}
