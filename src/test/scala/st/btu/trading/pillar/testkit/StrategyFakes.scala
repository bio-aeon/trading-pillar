package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import st.btu.trading.pillar.models.market.{Bar, Source, SourcedTicker, Ticker}
import st.btu.trading.pillar.storages.BacktestRunStorage
import st.btu.trading.pillar.strategy.backtest.{
  BacktestConfig,
  BacktestResult,
  BacktestRun,
  BacktestService
}
import st.btu.trading.pillar.strategy.*

import java.time.{LocalDate, ZonedDateTime}

object StrategyFakes {

  // A flat bar (open == high == low == close), convenient for tests that only care about
  // close-price series (indicators, signal generation).
  def flatBar(
    ticker: Ticker,
    date: LocalDate,
    price: BigDecimal,
    source: Source = Source.Yahoo
  ): Bar =
    Bar(ticker, date, price, price, price, price, price, 1000, source)

  // An OHLC bar with distinct open/close, for tests that care about the fill-at-next-open rule.
  def ohlcBar(
    ticker: Ticker,
    date: LocalDate,
    open: BigDecimal,
    high: BigDecimal,
    low: BigDecimal,
    close: BigDecimal,
    source: Source = Source.Yahoo
  ): Bar = Bar(ticker, date, open, high, low, close, close, 1000, source)

  // A strategy driven entirely by the caller-supplied onBar/proximity functions, for tests that
  // need to script exact engine behavior without depending on a reference strategy's indicators.
  final case class ScriptedStrategy(
    id: StrategyId,
    warmupBars: Int,
    onBarFn: (StrategyContext, Bar) => List[StrategySignal],
    name: String = "scripted",
    parameters: Map[String, String] = Map.empty,
    proximityFn: (StrategyContext, Bar) => Option[PreSignal] = (_, _) => None
  ) extends Strategy {
    def onBar(ctx: StrategyContext, bar: Bar): List[StrategySignal] = onBarFn(ctx, bar)

    override def proximity(ctx: StrategyContext, bar: Bar): Option[PreSignal] =
      proximityFn(ctx, bar)
  }

  // Always-Hold strategy, useful when a test only needs a valid, warmed-up strategy.
  def holdStrategy(id: StrategyId = StrategyId("hold"), warmupBars: Int = 1): ScriptedStrategy =
    ScriptedStrategy(id, warmupBars, (_, _) => List(StrategySignal.Hold("never trades")))

  // A ParameterizedStrategy driven entirely by the caller-supplied onBar/withParameters functions,
  // for BacktestSweep/SweepHandler tests that need full control over which grid point "wins" (or
  // fails withParameters) without depending on a reference strategy's indicator math.
  final case class ScriptedParameterizedStrategy(
    id: StrategyId,
    warmupBars: Int,
    onBarFn: (StrategyContext, Bar) => List[StrategySignal],
    withParametersFn: Map[String, String] => Either[String, Strategy],
    name: String = "scripted-parameterized",
    parameters: Map[String, String] = Map.empty
  ) extends ParameterizedStrategy {
    def onBar(ctx: StrategyContext, bar: Bar): List[StrategySignal] = onBarFn(ctx, bar)

    def withParameters(params: Map[String, String]): Either[String, Strategy] =
      withParametersFn(params)
  }

  trait FakeBacktestRunStorage extends BacktestRunStorage[IO] {
    def saved: IO[List[BacktestRun]]
  }

  def backtestRunStorage(initial: List[BacktestRun] = List.empty): IO[FakeBacktestRunStorage] =
    for {
      runsRef <- Ref.of[IO, List[BacktestRun]](initial)
      idRef <- Ref.of[IO, Long](initial.map(_.id.value).maxOption.getOrElse(0L))
    } yield new FakeBacktestRunStorage {
      def save(result: BacktestResult, createdAt: ZonedDateTime): IO[BacktestRunId] =
        for {
          nextId <- idRef.updateAndGet(_ + 1)
          runId = BacktestRunId(nextId)
          _ <- runsRef.update(_ :+ BacktestRun(runId, createdAt, result))
        } yield runId

      def get(id: BacktestRunId): IO[Option[BacktestRun]] = runsRef.get.map(_.find(_.id == id))

      def recent(limit: Int): IO[List[BacktestRun]] = runsRef.get.map(_.takeRight(limit).reverse)

      def saved: IO[List[BacktestRun]] = runsRef.get
    }

  // --- BacktestService (fake, used by BacktestHandlerSpec) ---

  trait FakeBacktestService extends BacktestService[IO] {
    def calls: IO[List[StrategyId]]

    // The source each single-ticker run (`run`, `runStrategy`) was asked to read, in call order.
    def readSources: IO[List[Source]]

    // The tickers, each with its source, of every portfolio run, in call order.
    def portfolioTickers: IO[List[List[SourcedTicker]]]
  }

  // `result` is invoked (and may fail) per call, so a single fake can script both success and the
  // StrategyNotFound/Failed error paths. `runStrategy` (the ad hoc/params= path) is scripted the
  // same way, keyed off the given Strategy's own id.
  def backtestService(result: StrategyId => IO[BacktestRun]): IO[FakeBacktestService] =
    for {
      callsRef <- Ref.of[IO, List[StrategyId]](List.empty)
      sourcesRef <- Ref.of[IO, List[Source]](List.empty)
      portfoliosRef <- Ref.of[IO, List[List[SourcedTicker]]](List.empty)
    } yield new FakeBacktestService {
      def run(
        strategyId: StrategyId,
        ticker: Ticker,
        source: Source,
        from: LocalDate,
        to: LocalDate,
        config: BacktestConfig
      ): IO[BacktestRun] =
        for {
          _ <- callsRef.update(_ :+ strategyId)
          _ <- sourcesRef.update(_ :+ source)
          run <- result(strategyId)
        } yield run

      def runStrategy(
        strategy: Strategy,
        ticker: Ticker,
        source: Source,
        from: LocalDate,
        to: LocalDate,
        config: BacktestConfig
      ): IO[BacktestRun] =
        for {
          _ <- callsRef.update(_ :+ strategy.id)
          _ <- sourcesRef.update(_ :+ source)
          run <- result(strategy.id)
        } yield run

      def runPortfolio(
        strategyId: StrategyId,
        tickers: List[SourcedTicker],
        from: LocalDate,
        to: LocalDate,
        config: BacktestConfig
      ): IO[BacktestRun] =
        for {
          _ <- callsRef.update(_ :+ strategyId)
          _ <- portfoliosRef.update(_ :+ tickers)
          run <- result(strategyId)
        } yield run

      def calls: IO[List[StrategyId]] = callsRef.get

      def readSources: IO[List[Source]] = sourcesRef.get

      def portfolioTickers: IO[List[List[SourcedTicker]]] = portfoliosRef.get
    }
}
