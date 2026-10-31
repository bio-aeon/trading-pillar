package st.btu.trading.pillar.alerts

import cats.MonadThrow
import cats.data.OptionT
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.{MarketDataSource, MarketDataSources}
import st.btu.trading.pillar.models.market.{Bar, Quote}
import st.btu.trading.pillar.storages.PriceHistoryStorage
import st.btu.trading.pillar.strategy.*
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZoneId, ZonedDateTime}

// No real brokerage position is available to trading-pillar (that lives in the consumer app), so
// a shadow position is derived purely from this binding's own live signal history: flat until a
// Buy alert fires, closed on the next Sell. It evolves bar by bar in stateRef, alongside the last
// bar date onBar ran for and the last warning sent, all of them recorded only once the caller has
// acted on them.
final class LiveStrategyServiceImpl[F[_]: Logging] private (
  priceHistory: PriceHistoryStorage[F],
  marketDataSources: MarketDataSources[F],
  stateRef: Ref[F, Map[String, LiveStrategyServiceImpl.LiveState]]
)(implicit F: MonadThrow[F])
    extends LiveStrategyService[F] {

  import LiveStrategyServiceImpl.{LiveState, Outcome}

  def evaluate[A](binding: StrategyBinding, strategy: Strategy, now: ZonedDateTime)(
    act: LiveEvaluation => F[A]
  ): F[A] =
    for {
      source <- marketDataSources.forTicker(binding.ticker)
      bars <- priceHistory.latestBars(binding.ticker, source.origin.source, strategy.warmupBars)
      history = BarHistory(bars.toVector)
      state <- stateRef.get.map(_.getOrElse(binding.ruleKey, LiveState.Initial))
      onBar = processNewBar(binding, strategy, history, state, now)
      outcome <- evaluateProximity(binding, strategy, source, history, onBar, now)
      result <- act(LiveEvaluation(outcome.alerts, outcome.signals))
      _ <- stateRef.update(_.updated(binding.ruleKey, outcome.state))
    } yield result

  private def processNewBar(
    binding: StrategyBinding,
    strategy: Strategy,
    history: BarHistory,
    state: LiveState,
    now: ZonedDateTime
  ): Outcome = {
    val newBar = history.last.filter(bar =>
      history.size >= strategy.warmupBars && state.lastBarDate.forall(_.isBefore(bar.date))
    )
    newBar.fold(Outcome(state, Nil, Nil)) { bar =>
      val ctx = StrategyContext(binding.ticker, history, state.position, PortfolioView(0, 0))
      val signals = strategy.onBar(ctx, bar)
      val position = applySignals(state.position, signals, bar)
      Outcome(
        state.copy(lastBarDate = Some(bar.date), position = position),
        signals.flatMap(toSignalAlert(binding, now, bar, _)),
        signals.filter(isActionable)
      )
    }
  }

  private def isActionable(signal: StrategySignal): Boolean =
    signal match {
      case StrategySignal.Buy(_, _, _) => true
      case StrategySignal.Sell(_, _, _) => true
      case StrategySignal.Hold(_) => false
    }

  private def applySignals(
    position: Option[OpenPosition],
    signals: List[StrategySignal],
    bar: Bar
  ): Option[OpenPosition] =
    signals.foldLeft(position) { (pos, signal) =>
      signal match {
        case StrategySignal.Buy(ticker, _, _) =>
          pos.orElse(Some(OpenPosition(ticker, 1L, bar.close, bar.date)))
        case StrategySignal.Sell(_, _, _) => None
        case StrategySignal.Hold(_) => pos
      }
    }

  private def toSignalAlert(
    binding: StrategyBinding,
    now: ZonedDateTime,
    bar: Bar,
    signal: StrategySignal
  ): Option[Alert] =
    signal match {
      case StrategySignal.Buy(ticker, _, reason) =>
        Some(
          Alert(
            binding.ruleKey,
            Some(ticker),
            AlertKind.StrategyBuy,
            s"${ticker.value} strategy BUY",
            reason,
            Some(bar.close),
            now
          )
        )
      case StrategySignal.Sell(ticker, _, reason) =>
        Some(
          Alert(
            binding.ruleKey,
            Some(ticker),
            AlertKind.StrategySell,
            s"${ticker.value} strategy SELL",
            reason,
            Some(bar.close),
            now
          )
        )
      case StrategySignal.Hold(_) => None
    }

  private def evaluateProximity(
    binding: StrategyBinding,
    strategy: Strategy,
    source: MarketDataSource[F],
    history: BarHistory,
    onBar: Outcome,
    now: ZonedDateTime
  ): F[Outcome] =
    (for {
      quote <- OptionT(source.latestQuote(binding.ticker))
      info <- OptionT(source.instrumentInfo(binding.ticker))
      zone <- OptionT.liftF(F.catchNonFatal(ZoneId.of(info.timezone)))
      liveBar = provisionalBar(quote, zone)
      // Once the quote's session has closed, its stored bar gives way to the quote, so that the
      // session counts once.
      earlierSessions = BarHistory(history.bars.filter(_.date.isBefore(liveBar.date)))
      state = onBar.state
      ctx = StrategyContext(binding.ticker, earlierSessions, state.position, PortfolioView(0, 0))
      warning <- OptionT.fromOption[F](
        strategy.proximity(ctx, liveBar).filterNot(state.repeats(liveBar.date, _))
      )
    } yield onBar.copy(
      state = state.warned(liveBar.date, warning),
      alerts = onBar.alerts :+ toProximityAlert(binding, now, warning)
    )).getOrElse(onBar)

  private def provisionalBar(quote: Quote, zone: ZoneId): Bar = {
    val price = quote.price
    val session = quote.asOf.withZoneSameInstant(zone).toLocalDate
    Bar(quote.ticker, session, price, price, price, price, price, 0L, quote.source)
  }

  private def toProximityAlert(
    binding: StrategyBinding,
    now: ZonedDateTime,
    preSignal: PreSignal
  ): Alert = {
    val kind = preSignal.side match {
      case Side.Buy => AlertKind.StrategyPreBuy
      case Side.Sell => AlertKind.StrategyPreSell
    }
    Alert(
      binding.ruleKey,
      Some(binding.ticker),
      kind,
      s"${binding.ticker.value} approaching strategy signal",
      preSignal.reason,
      Some(preSignal.distanceToTrigger),
      now
    )
  }
}

object LiveStrategyServiceImpl {

  private[alerts] final case class LiveState(
    lastBarDate: Option[LocalDate],
    position: Option[OpenPosition],
    lastWarning: Option[SentWarning]
  ) {

    // A standing warning goes out once per session: within the session it repeats the last one
    // sent until it is of the other side, or closer to its signal and at most half as far.
    def repeats(session: LocalDate, warning: PreSignal): Boolean =
      lastWarning.exists { sent =>
        val distance = warning.distanceToTrigger
        val halved = distance < sent.distance && distance * 2 <= sent.distance
        sent.session == session && sent.side == warning.side && !halved
      }

    def warned(session: LocalDate, warning: PreSignal): LiveState =
      copy(lastWarning = Some(SentWarning(session, warning.side, warning.distanceToTrigger)))
  }

  private[alerts] object LiveState {
    val Initial: LiveState = LiveState(None, None, None)
  }

  private[alerts] final case class SentWarning(session: LocalDate, side: Side, distance: BigDecimal)

  // What one evaluation found for a binding, with the state to record once it is acted on.
  private final case class Outcome(
    state: LiveState,
    alerts: List[Alert],
    signals: List[StrategySignal]
  )

  /** Wires a [[LiveStrategyService]] with no binding state recorded yet. */
  def create[I[_]: Sync, F[_]: Sync](
    priceHistory: PriceHistoryStorage[F],
    marketDataSources: MarketDataSources[F]
  )(implicit logs: Logs[I, F]): I[LiveStrategyServiceImpl[F]] =
    for {
      stateRef <- Ref.in[I, F, Map[String, LiveState]](Map.empty)
      service <- logs
        .forService[LiveStrategyService[F]]
        .map(implicit log =>
          new LiveStrategyServiceImpl[F](priceHistory, marketDataSources, stateRef)
        )
    } yield service
}
