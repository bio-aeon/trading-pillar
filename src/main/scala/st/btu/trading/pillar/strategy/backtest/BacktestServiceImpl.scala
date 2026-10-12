package st.btu.trading.pillar.strategy.backtest

import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import cats.{Functor, MonadThrow}
import st.btu.trading.pillar.models.market.{Source, Ticker}
import st.btu.trading.pillar.storages.{BacktestRunStorage, PriceHistoryStorage}
import st.btu.trading.pillar.strategy.{Strategy, StrategyId, StrategyRegistry}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

final class BacktestServiceImpl[F[_]: Logging] private (
  registry: StrategyRegistry[F],
  priceHistory: PriceHistoryStorage[F],
  storage: BacktestRunStorage[F]
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends BacktestService[F] {

  def run(
    strategyId: StrategyId,
    ticker: Ticker,
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun] =
    for {
      strategy <- registry.get(strategyId).flatMap {
        case Some(s) => F.pure(s)
        case None => F.raiseError[Strategy](BacktestServiceError.StrategyNotFound(strategyId))
      }
      run <- runAndPersist(strategy, ticker, source, from, to, config)
    } yield run

  def runStrategy(
    strategy: Strategy,
    ticker: Ticker,
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun] = runAndPersist(strategy, ticker, source, from, to, config)

  def runPortfolio(
    strategyId: StrategyId,
    tickers: List[Ticker],
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun] =
    for {
      strategy <- registry.get(strategyId).flatMap {
        case Some(s) => F.pure(s)
        case None => F.raiseError[Strategy](BacktestServiceError.StrategyNotFound(strategyId))
      }
      run <- runPortfolioAndPersist(strategy, tickers, source, from, to, config)
    } yield run

  private def runPortfolioAndPersist(
    strategy: Strategy,
    tickers: List[Ticker],
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun] =
    for {
      barsByTicker <- tickers
        .traverse(t => priceHistory.bars(t, source, from, to).map(t -> _))
        .map(_.toMap)
      portfolioResult <- BacktestEngine.runPortfolio(strategy, barsByTicker, config) match {
        case Right(r) => F.pure(r)
        case Left(e) => F.raiseError[PortfolioBacktestResult](BacktestServiceError.Failed(e))
      }
      now <- nowF
      combined = toBacktestResult(portfolioResult)
      runId <- storage.save(combined, now)
      totalReturn = combined.metrics.totalReturn
      finalEquity = combined.metrics.finalEquity
      tradeCount = combined.trades.size
      _ <-
        info"Backtest ${strategy.id.value} on ${combined.ticker.value}: $tradeCount trade(s), total return $totalReturn, final equity $finalEquity"
    } yield BacktestRun(runId, now, combined)

  // Projects a PortfolioBacktestResult into the single-ticker-shaped BacktestResult
  // BacktestRunStorage.save already persists: ticker is every ticker joined by "," and trades are
  // every ticker's own trades concatenated in ticker order, then sorted by date (stable, so same-
  // date trades keep that deterministic ticker order).
  private def toBacktestResult(result: PortfolioBacktestResult): BacktestResult =
    BacktestResult(
      result.strategyId,
      Ticker(result.tickers.map(_.value).mkString(",")),
      result.parameters,
      result.dateFrom,
      result.dateTo,
      result.config,
      result.tickers
        .flatMap(t => result.tradesByTicker.getOrElse(t, Nil))
        .sortBy(_.date.toEpochDay),
      result.equityCurve,
      result.metrics,
      result.skipped
    )

  private def runAndPersist(
    strategy: Strategy,
    ticker: Ticker,
    source: Source,
    from: LocalDate,
    to: LocalDate,
    config: BacktestConfig
  ): F[BacktestRun] =
    for {
      bars <- priceHistory.bars(ticker, source, from, to)
      result <- BacktestEngine.run(strategy, bars, config) match {
        case Right(r) => F.pure(r)
        case Left(e) => F.raiseError[BacktestResult](BacktestServiceError.Failed(e))
      }
      now <- nowF
      runId <- storage.save(result, now)
      totalReturn = result.metrics.totalReturn
      finalEquity = result.metrics.finalEquity
      tradeCount = result.trades.size
      _ <-
        info"Backtest ${strategy.id.value} on ${ticker.value}: $tradeCount trade(s), total return $totalReturn, final equity $finalEquity"
    } yield BacktestRun(runId, now, result)

  private def nowF: F[ZonedDateTime] =
    clock.realTimeInstant.map(ZonedDateTime.ofInstant(_, ZoneOffset.UTC))
}

object BacktestServiceImpl {

  /** Wires a [[BacktestService]] backed by `registry`, `priceHistory` and `storage`. */
  def create[I[_]: Functor, F[_]: MonadThrow: Clock](
    registry: StrategyRegistry[F],
    priceHistory: PriceHistoryStorage[F],
    storage: BacktestRunStorage[F]
  )(implicit logs: Logs[I, F]): I[BacktestService[F]] =
    logs
      .forService[BacktestService[F]]
      .map(implicit log => new BacktestServiceImpl[F](registry, priceHistory, storage))
}
