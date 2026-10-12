package st.btu.trading.pillar.strategy.backtest

import st.btu.trading.pillar.analytics.Performance
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.strategy.indicators.Indicators.divide
import st.btu.trading.pillar.strategy.*

import java.time.LocalDate

/** Pure, deterministic backtest simulation. A signal `strategy.onBar` produces on bar `t` becomes a
  * pending order filled at bar `t+1`'s open (no look-ahead); `proximity` is not used here, only by
  * live "approaching" alerts.
  */
object BacktestEngine {

  /** Runs `strategy` once over `bars`.
    *
    * @return
    *   a `Left` if `bars` is empty, not chronological with unique dates, or shorter than
    *   `strategy.warmupBars`
    */
  def run(
    strategy: Strategy,
    bars: List[Bar],
    config: BacktestConfig
  ): Either[BacktestError, BacktestResult] =
    validate(strategy, bars).map(simulate(strategy, _, config))

  /** Runs `strategy` over every ticker in `barsByTicker` sharing one cash balance: on each date
    * merged across every ticker's own bars, tickers fill and signal in alphabetical order with the
    * same next-open rule as [[run]], except a ticker's `FractionOfEquity` sizing sees the whole
    * portfolio's equity rather than just its own position. A ticker with no bar on a given date is
    * skipped that date; each ticker's own last bar still ends its own signals independently of the
    * others' date ranges.
    *
    * @return
    *   a `Left` under the same conditions as [[run]], checked per ticker
    */
  def runPortfolio(
    strategy: Strategy,
    barsByTicker: Map[Ticker, List[Bar]],
    config: BacktestConfig
  ): Either[BacktestError, PortfolioBacktestResult] =
    validatePortfolio(strategy, barsByTicker).map(simulatePortfolio(strategy, _, config))

  private def validate(strategy: Strategy, bars: List[Bar]): Either[BacktestError, List[Bar]] =
    if (bars.isEmpty) Left(BacktestError.NoBars)
    else if (!chronologicalWithUniqueDates(bars))
      Left(BacktestError.InvalidBars("Bars must be chronological with unique dates"))
    else if (bars.size < strategy.warmupBars)
      Left(BacktestError.NotEnoughBars(strategy.warmupBars, bars.size))
    else Right(bars)

  // Validates every ticker's own bars with the exact same rule `run` uses, in alphabetical order
  // so a multi-ticker failure is deterministic regardless of the input map's iteration order.
  private def validatePortfolio(
    strategy: Strategy,
    barsByTicker: Map[Ticker, List[Bar]]
  ): Either[BacktestError, Map[Ticker, List[Bar]]] =
    if (barsByTicker.isEmpty) Left(BacktestError.NoBars)
    else {
      val sorted = barsByTicker.toList.sortBy { case (ticker, _) => ticker.value }
      validateEach(strategy, sorted) match {
        case Left(e) => Left(e)
        case Right(_) => Right(barsByTicker)
      }
    }

  @scala.annotation.tailrec
  private def validateEach(
    strategy: Strategy,
    remaining: List[(Ticker, List[Bar])]
  ): Either[BacktestError, Unit] =
    remaining match {
      case Nil => Right(())
      case (_, bars) :: rest =>
        validate(strategy, bars) match {
          case Left(e) => Left(e)
          case Right(_) => validateEach(strategy, rest)
        }
    }

  private def chronologicalWithUniqueDates(bars: List[Bar]): Boolean =
    bars.sliding(2).forall {
      case Seq(a, b) => a.date.isBefore(b.date)
      case _ => true
    }

  // Mutable-looking but purely functional accumulator threaded through the bar-by-bar fold.
  private final case class State(
    history: BarHistory,
    cash: BigDecimal,
    position: Option[OpenPosition],
    pending: List[StrategySignal],
    trades: Vector[Trade],
    equityCurve: Vector[EquityPoint],
    skipped: Vector[SkippedSignal]
  )

  private def simulate(
    strategy: Strategy,
    bars: List[Bar],
    config: BacktestConfig
  ): BacktestResult = {
    val ticker = bars.head.ticker
    val lastIndex = bars.size - 1
    val initial = State(
      BarHistory.empty,
      config.initialCash,
      None,
      Nil,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

    val end = bars.zipWithIndex.foldLeft(initial) { case (state, (bar, idx)) =>
      val history = BarHistory(state.history.bars :+ bar)

      // 1) fill any orders pending from the previous bar's signals, at this bar's open
      val (cashAfterFill, positionAfterFill, newTrades, fillSkips) =
        fillPending(state.pending, bar, state.cash, state.position, BigDecimal(0), config)

      // 2) mark this bar's equity, using its close
      val equity = cashAfterFill + positionAfterFill
        .map(p => BigDecimal(p.quantity) * bar.close)
        .getOrElse(BigDecimal(0))
      val equityPoint = EquityPoint(
        bar.date,
        equity,
        cashAfterFill,
        positionAfterFill.map(_.quantity).getOrElse(0L)
      )

      // 3) ask the strategy for new signals once warmed up; they become pending for the next bar
      val newSignals =
        if (history.size >= strategy.warmupBars) {
          val ctx = StrategyContext(
            ticker,
            history,
            positionAfterFill,
            PortfolioView(cashAfterFill, equity)
          )
          strategy.onBar(ctx, bar)
        } else Nil

      val isLast = idx == lastIndex
      val (nextPending, unfilledSkips) =
        if (isLast) (Nil, unfillable(newSignals, bar))
        else (newSignals, Vector.empty[SkippedSignal])

      State(
        history,
        cashAfterFill,
        positionAfterFill,
        nextPending,
        state.trades ++ newTrades,
        state.equityCurve :+ equityPoint,
        state.skipped ++ fillSkips ++ unfilledSkips
      )
    }

    val trades = end.trades.toList
    val equityCurve = end.equityCurve.toList
    val metrics =
      Metrics.compute(config.initialCash, equityCurve, trades, bars.head.open, bars.last.close)

    BacktestResult(
      strategy.id,
      ticker,
      strategy.parameters,
      bars.head.date,
      bars.last.date,
      config,
      trades,
      equityCurve,
      metrics,
      end.skipped.toList
    )
  }

  // Per-ticker accumulator for the portfolio fold below - the same shape as single-ticker State
  // minus `cash` (shared across every ticker) and `equityCurve` (one combined curve, not one per
  // ticker).
  private final case class TickerState(
    history: BarHistory,
    position: Option[OpenPosition],
    pending: List[StrategySignal],
    trades: Vector[Trade],
    skipped: Vector[SkippedSignal]
  )

  private object TickerState {
    val empty: TickerState = TickerState(BarHistory.empty, None, Nil, Vector.empty, Vector.empty)
  }

  private final case class PortfolioState(
    cash: BigDecimal,
    tickers: Map[Ticker, TickerState],
    lastClose: Map[Ticker, BigDecimal],
    equityCurve: Vector[EquityPoint]
  )

  private def simulatePortfolio(
    strategy: Strategy,
    barsByTicker: Map[Ticker, List[Bar]],
    config: BacktestConfig
  ): PortfolioBacktestResult = {
    val tickers = barsByTicker.keys.toList.sortBy(_.value)
    val barsByDate: Map[Ticker, Map[LocalDate, Bar]] =
      barsByTicker.view.mapValues(_.map(b => b.date -> b).toMap).toMap
    val lastDate: Map[Ticker, LocalDate] = barsByTicker.view.mapValues(_.last.date).toMap
    val allDates: List[LocalDate] = barsByTicker.values.flatten.map(_.date).toSet.toList.sorted

    val initial = PortfolioState(
      config.initialCash,
      tickers.map(_ -> TickerState.empty).toMap,
      Map.empty,
      Vector.empty
    )

    val end = allDates.foldLeft(initial) { (state, date) =>
      // Today's reference price per ticker for sizing during fills: its own open if it has a bar
      // today, else its last known close. A ticker with neither yet (not listed/traded so far)
      // falls back to 0, which only ever multiplies a None position (see the invariant below).
      val openPx: Map[Ticker, BigDecimal] = tickers.map { t =>
        t -> barsByDate(t)
          .get(date)
          .map(_.open)
          .orElse(state.lastClose.get(t))
          .getOrElse(BigDecimal(0))
      }.toMap

      // 1) fill each ticker's pending orders (from its own previous bar's signals) at today's
      // open, in alphabetical order, sharing one cash balance. otherPositionsValue freezes every
      // OTHER ticker's mark-to-market so a ticker's FractionOfEquity sizing sees the whole
      // portfolio's equity, not just its own position; it reflects fills already applied earlier
      // today for alphabetically-prior tickers, which is the actual current state.
      val (cashAfterFills, tickersAfterFills) =
        tickers.foldLeft((state.cash, state.tickers)) { case ((cash, tsByTicker), ticker) =>
          barsByDate(ticker).get(date) match {
            case None => (cash, tsByTicker)
            case Some(bar) =>
              val ts = tsByTicker(ticker)
              val otherPositionsValue = tickers
                .filter(_ != ticker)
                .map { other =>
                  tsByTicker(other).position
                    .map(p => BigDecimal(p.quantity) * openPx(other))
                    .getOrElse(BigDecimal(0))
                }
                .sum
              val (newCash, newPosition, newTrades, fillSkips) =
                fillPending(ts.pending, bar, cash, ts.position, otherPositionsValue, config)
              val updated = ts.copy(
                position = newPosition,
                pending = Nil,
                trades = ts.trades ++ newTrades,
                skipped = ts.skipped ++ fillSkips
              )
              (newCash, tsByTicker.updated(ticker, updated))
          }
        }

      // 2) mark today's bar into history (for tickers that have one), value every position at
      // today's close (or the last known close for a ticker with no bar today) and record one
      // portfolio-wide EquityPoint.
      val closePx: Map[Ticker, BigDecimal] = tickers.map { t =>
        t -> barsByDate(t)
          .get(date)
          .map(_.close)
          .orElse(state.lastClose.get(t))
          .getOrElse(BigDecimal(0))
      }.toMap
      val tickersWithHistory: Map[Ticker, TickerState] = tickersAfterFills.map { case (t, ts) =>
        barsByDate(t).get(date) match {
          case Some(bar) => t -> ts.copy(history = BarHistory(ts.history.bars :+ bar))
          case None => t -> ts
        }
      }
      val totalPositionsValue = tickers
        .map(t =>
          tickersWithHistory(t).position
            .map(p => BigDecimal(p.quantity) * closePx(t))
            .getOrElse(BigDecimal(0))
        )
        .sum
      val totalEquity = cashAfterFills + totalPositionsValue
      val totalQuantity = tickersWithHistory.values.flatMap(_.position.map(_.quantity)).sum
      val equityPoint = EquityPoint(date, totalEquity, cashAfterFills, totalQuantity)

      // 3) ask the strategy for each warmed-up ticker's new signals; on that ticker's own last
      // bar they have no next bar to fill against and become skipped/unfilled instead of pending.
      val tickersWithSignals: Map[Ticker, TickerState] = tickers.map { t =>
        val ts = tickersWithHistory(t)
        barsByDate(t).get(date) match {
          case Some(bar) if ts.history.size >= strategy.warmupBars =>
            val ctx =
              StrategyContext(
                t,
                ts.history,
                ts.position,
                PortfolioView(cashAfterFills, totalEquity)
              )
            val newSignals = strategy.onBar(ctx, bar)
            val isLast = date == lastDate(t)
            val (nextPending, unfilledSkips) =
              if (isLast) (Nil, unfillable(newSignals, bar))
              else (newSignals, Vector.empty[SkippedSignal])
            t -> ts.copy(pending = nextPending, skipped = ts.skipped ++ unfilledSkips)
          case _ => t -> ts
        }
      }.toMap

      PortfolioState(cashAfterFills, tickersWithSignals, closePx, state.equityCurve :+ equityPoint)
    }

    val tradesByTicker: Map[Ticker, List[Trade]] = end.tickers.view.mapValues(_.trades.toList).toMap
    val skipped: List[SkippedSignal] =
      end.tickers.values.flatMap(_.skipped).toList.sortBy(_.date.toEpochDay)
    val equityCurve = end.equityCurve.toList
    val tradeCount = tickers.map(t => tradesByTicker.getOrElse(t, Nil).size).sum

    // Round trips must stay isolated per ticker (a buy on one ticker and a sell on another must
    // never net together into one "position returning to flat"), so each ticker's own trades are
    // run through Metrics.roundTrips separately and only the resulting PnLs are combined.
    val roundTripPnls = tickers.flatMap(t => Metrics.roundTrips(tradesByTicker.getOrElse(t, Nil)))
    val winRate =
      if (roundTripPnls.isEmpty) None
      else Some(divide(BigDecimal(roundTripPnls.count(_ > 0)), BigDecimal(roundTripPnls.size)))

    // firstOpen/lastClose are placeholders: Metrics.compute only uses them to derive
    // buyAndHoldReturn (everything else comes from equityCurve/trades), and that field is
    // overridden just below with the portfolio's own equal-weight definition.
    val baseMetrics =
      Metrics.compute(config.initialCash, equityCurve, Nil, BigDecimal(1), BigDecimal(1))
    val metrics = baseMetrics.copy(
      winRate = winRate,
      tradeCount = tradeCount,
      roundTrips = roundTripPnls.size,
      buyAndHoldReturn = equalWeightBuyAndHold(tickers, barsByTicker, config.initialCash)
    )

    PortfolioBacktestResult(
      strategy.id,
      tickers,
      strategy.parameters,
      allDates.min,
      allDates.max,
      config,
      tradesByTicker,
      equityCurve,
      metrics,
      skipped
    )
  }

  // Equal-weight buy-and-hold: split initialCash evenly across every ticker, buy at each one's
  // own first open, value the result at each one's own last close.
  private def equalWeightBuyAndHold(
    tickers: List[Ticker],
    barsByTicker: Map[Ticker, List[Bar]],
    initialCash: BigDecimal
  ): BigDecimal =
    if (tickers.isEmpty) BigDecimal(0)
    else {
      val perTicker = divide(initialCash, BigDecimal(tickers.size))
      val finalValue = tickers.map { t =>
        val bars = barsByTicker(t)
        divide(perTicker, bars.head.open) * bars.last.close
      }.sum
      Performance.totalReturn(initialCash, finalValue)
    }

  // Signals produced on the last bar have no next bar to fill against.
  private def unfillable(signals: List[StrategySignal], bar: Bar): Vector[SkippedSignal] =
    signals.collect {
      case StrategySignal.Buy(_, _, reason) =>
        SkippedSignal(bar.date, s"Unfilled at end of backtest: $reason")
      case StrategySignal.Sell(_, _, reason) =>
        SkippedSignal(bar.date, s"Unfilled at end of backtest: $reason")
    }.toVector

  private def fillPending(
    pending: List[StrategySignal],
    bar: Bar,
    cash: BigDecimal,
    position: Option[OpenPosition],
    otherPositionsValue: BigDecimal,
    config: BacktestConfig
  ): (BigDecimal, Option[OpenPosition], Vector[Trade], Vector[SkippedSignal]) = {
    val zero = (cash, position, Vector.empty[Trade], Vector.empty[SkippedSignal])
    pending.foldLeft(zero) { case ((cashAcc, posAcc, trades, skips), signal) =>
      fillOne(signal, bar, cashAcc, posAcc, otherPositionsValue, config) match {
        case FillOutcome.Filled(newCash, newPos, trade) => (newCash, newPos, trades :+ trade, skips)
        case FillOutcome.Skipped(newCash, newPos, skip) => (newCash, newPos, trades, skips :+ skip)
        case FillOutcome.NoOp(newCash, newPos) => (newCash, newPos, trades, skips)
      }
    }
  }

  // otherPositionsValue is the mark-to-market value of every OTHER ticker's current position
  // (0 for the single-ticker path), added into the equity used for FractionOfEquity/Notional
  // sizing so a multi-ticker caller sizes against the whole portfolio, not just this position.
  private def fillOne(
    signal: StrategySignal,
    bar: Bar,
    cash: BigDecimal,
    position: Option[OpenPosition],
    otherPositionsValue: BigDecimal,
    config: BacktestConfig
  ): FillOutcome =
    signal match {
      case StrategySignal.Buy(ticker, sizing, reason) =>
        val fillPrice = SizingResolution.buyFillPrice(bar.open, config.slippageBps)
        val ownValue =
          position.map(p => BigDecimal(p.quantity) * bar.open).getOrElse(BigDecimal(0))
        val equity = cash + otherPositionsValue + ownValue
        val qty =
          SizingResolution.resolveBuyQuantity(
            sizing,
            cash,
            equity,
            fillPrice,
            config.commissionRate
          )
        if (qty <= 0L)
          FillOutcome.Skipped(
            cash,
            position,
            SkippedSignal(bar.date, s"Buy resolved to zero quantity ($reason)")
          )
        else {
          val notional = BigDecimal(qty) * fillPrice
          val commission = config.commissionRate * notional
          val newCash = cash - notional - commission
          val newPosition = applyBuy(position, ticker, qty, fillPrice, bar.date)
          FillOutcome.Filled(
            newCash,
            Some(newPosition),
            Trade(bar.date, Side.Buy, qty, fillPrice, commission, reason)
          )
        }

      case StrategySignal.Sell(_, sizing, reason) =>
        position match {
          case None =>
            FillOutcome.Skipped(
              cash,
              position,
              SkippedSignal(bar.date, s"Sell without a position ($reason)")
            )
          case Some(pos) =>
            val fillPrice = SizingResolution.sellFillPrice(bar.open, config.slippageBps)
            val equity = cash + otherPositionsValue + BigDecimal(pos.quantity) * bar.open
            val qty = SizingResolution.resolveSellQuantity(sizing, pos.quantity, equity, fillPrice)
            if (qty <= 0L)
              FillOutcome.Skipped(
                cash,
                position,
                SkippedSignal(bar.date, s"Sell resolved to zero quantity ($reason)")
              )
            else {
              val notional = BigDecimal(qty) * fillPrice
              val commission = config.commissionRate * notional
              val newCash = cash + notional - commission
              val newPosition = applySell(pos, qty)
              FillOutcome.Filled(
                newCash,
                newPosition,
                Trade(bar.date, Side.Sell, qty, fillPrice, commission, reason)
              )
            }
        }

      case StrategySignal.Hold(_) => FillOutcome.NoOp(cash, position)
    }

  private def applyBuy(
    position: Option[OpenPosition],
    ticker: Ticker,
    qty: Long,
    fillPrice: BigDecimal,
    date: LocalDate
  ): OpenPosition =
    position match {
      case Some(p) =>
        val totalQty = p.quantity + qty
        val newAvgCost = divide(
          BigDecimal(p.quantity) * p.averageCost + BigDecimal(qty) * fillPrice,
          BigDecimal(totalQty)
        )
        p.copy(quantity = totalQty, averageCost = newAvgCost)
      case None =>
        OpenPosition(ticker, qty, fillPrice, date)
    }

  private def applySell(position: OpenPosition, qty: Long): Option[OpenPosition] = {
    val remaining = position.quantity - qty
    if (remaining <= 0L) None else Some(position.copy(quantity = remaining))
  }
}
