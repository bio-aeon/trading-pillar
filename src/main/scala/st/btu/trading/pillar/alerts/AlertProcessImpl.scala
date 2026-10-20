package st.btu.trading.pillar.alerts

import cats.effect.{Async, Ref, Sync, Temporal}
import cats.syntax.applicative.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import fs2.Stream
import st.btu.trading.pillar.data.{ProcessContext, TraceIds, UtcClock}
import st.btu.trading.pillar.gateways.MarketDataSources
import st.btu.trading.pillar.models.market.{MarketDataError, Quote, Source, Ticker}
import st.btu.trading.pillar.paper.PaperBroker
import st.btu.trading.pillar.storages.{
  AlertRuleStorage,
  PriceHistoryStorage,
  StrategyBindingStorage
}
import st.btu.trading.pillar.strategy.{StrategyRegistry, StrategySignal}
import st.btu.trading.pillar.strategy.indicators.Indicators
import tofu.WithLocal
import tofu.generate.GenUUID
import tofu.logging.{Logging, Logs}
import tofu.syntax.context.*
import tofu.syntax.logging.*

import java.time.ZonedDateTime

final class AlertProcessImpl[F[_]: Temporal: Logging: GenUUID](
  strategyBindings: StrategyBindingStorage[F],
  alertRules: AlertRuleStorage[F],
  registry: StrategyRegistry[F],
  liveStrategyService: LiveStrategyService[F],
  marketDataSources: MarketDataSources[F],
  priceHistory: PriceHistoryStorage[F],
  dispatcher: AlertDispatcher[F],
  paperBroker: PaperBroker[F],
  systemAlerts: SystemAlerts[F],
  // Last observed quote per ticker, used by AlertEngine.evaluatePriceRule to detect a cross.
  previousQuotes: Ref[F, Map[Ticker, Quote]],
  config: AlertProcessConfig
)(implicit WL: WithLocal[F, ProcessContext])
    extends AlertProcess[F] {

  def run: Stream[F, Unit] = tick

  private def tick: Stream[F, Unit] =
    Stream
      .eval(runBatch)
      .flatMap(_ => Stream.sleep(config.pollInterval))
      .repeat
      .handleErrorWith { e =>
        Stream.eval(
          errorCause"Alert process loop failure" (e) >>
            systemAlerts.raise(
              "process-crashed:alerts",
              "Alert process crashed",
              s"Loop failure: ${Option(e.getMessage).getOrElse(e.toString)}"
            )
        ) >>
          Stream.sleep(config.pollInterval) >> tick
      }

  private def runBatch: F[Unit] =
    for {
      now <- UtcClock.nowUtc[F]
      bindings <- strategyBindings.allEnabled
      rules <- alertRules.allEnabledPriceRules
      _ <- processBindings(bindings, now).compile.drain
      _ <- processRules(rules, now).compile.drain
    } yield ()

  private def processBindings(
    bindings: List[StrategyBinding],
    now: ZonedDateTime
  ): Stream[F, Unit] =
    Stream.emits(bindings).flatMap(processBinding(_, now))

  private def processBinding(binding: StrategyBinding, now: ZonedDateTime): Stream[F, Unit] =
    Stream
      .eval(TraceIds.next[F])
      .flatMap { uuid =>
        Stream.eval(
          evaluateAndDispatchBinding(binding, now)
            .local(_.copy(traceId = uuid, accountId = binding.accountId))
        )
      }
      .handleErrorWith { e =>
        Stream.eval(errorCause"Alert evaluation failed for binding ${binding.ruleKey}" (e))
      }

  private def evaluateAndDispatchBinding(binding: StrategyBinding, now: ZonedDateTime): F[Unit] =
    registry.get(binding.strategyId).flatMap {
      case None =>
        warn"Strategy ${binding.strategyId.value} not registered for binding ${binding.ruleKey}"
      case Some(strategy) =>
        liveStrategyService.evaluate(binding, strategy, now).flatMap { evaluation =>
          dispatchAll(evaluation.alerts, binding.policy, binding.channels) >>
            executePaperSignals(binding, evaluation.signals, now)
        }
    }

  // Paper executions are independent of (and not subject to) the alert guards above: a binding in
  // paper mode trades on every Buy/Sell signal regardless of whether the matching alert was
  // delivered, muted or suppressed. Only fetches a quote (and only once, reused for every signal -
  // onBar normally produces at most one per poll anyway) when there is actually something to
  // execute, so a signal-mode binding never pays for an extra quote lookup it doesn't need.
  private def executePaperSignals(
    binding: StrategyBinding,
    signals: List[StrategySignal],
    now: ZonedDateTime
  ): F[Unit] =
    if (binding.mode != BindingMode.Paper || signals.isEmpty) ().pure[F]
    else
      marketDataSources.forTicker(binding.ticker).flatMap(_.latestQuote(binding.ticker)).flatMap {
        case None =>
          warn"No quote available for ${binding.ticker.value}; skipping paper execution for ${binding.ruleKey}"
        case Some(quote) =>
          signals.traverse(executePaperSignal(binding, _, quote, now)).void
      }

  private def executePaperSignal(
    binding: StrategyBinding,
    signal: StrategySignal,
    quote: Quote,
    now: ZonedDateTime
  ): F[Unit] =
    paperBroker.execute(binding, signal, quote, now).flatMap { order =>
      val reasonSuffix = order.reason.fold("")(r => s" ($r)")
      info"Paper order ${order.status.value} for ${binding.ruleKey}: ${order.side.toString} ${order.quantity} ${binding.ticker.value}$reasonSuffix"
    }

  private def processRules(rules: List[PriceCrossed], now: ZonedDateTime): Stream[F, Unit] =
    Stream.emits(rules).flatMap(processRule(_, now))

  private def processRule(rule: PriceCrossed, now: ZonedDateTime): Stream[F, Unit] =
    Stream
      .eval(TraceIds.next[F])
      .flatMap { uuid =>
        Stream.eval(
          evaluateAndDispatchRule(rule, now)
            .local(_.copy(traceId = uuid, accountId = rule.accountId))
        )
      }
      .handleErrorWith {
        case MarketDataError.RateLimited(retryAfter) =>
          Stream.eval(warn"Rate limited evaluating ${rule.ticker.value}; backing off") ++
            Stream.sleep[F](retryAfter.getOrElse(config.rateLimitBackoff))
        case e =>
          Stream.eval(errorCause"Alert evaluation failed for rule ${rule.ruleKey}" (e))
      }

  private def evaluateAndDispatchRule(rule: PriceCrossed, now: ZonedDateTime): F[Unit] =
    marketDataSources.forTicker(rule.ticker).flatMap { source =>
      source.latestQuote(rule.ticker).flatMap {
        case None =>
          warn"No quote available for ${rule.ticker.value}; skipping rule ${rule.ruleKey}"
        case Some(current) =>
          for {
            previous <- previousQuotes.get.map(_.get(rule.ticker))
            atr <- atrFor(rule, source.source)
            alerts = AlertEngine.evaluatePriceRule(rule, previous, current, atr)
            _ <- previousQuotes.update(_.updated(rule.ticker, current))
            _ <- dispatchAll(alerts, rule.policy, rule.channels)
          } yield ()
      }
    }

  private def atrFor(rule: PriceCrossed, source: Source): F[Option[BigDecimal]] =
    rule.proximity match {
      case None => Option.empty[BigDecimal].pure[F]
      case Some(proximity) =>
        proximity match {
          case Proximity.Atr(_, period) =>
            priceHistory
              .latestBars(rule.ticker, source, period + 1)
              .map(bars => Indicators.atr(bars.toVector, period))
          case Proximity.Percent(_) => Option.empty[BigDecimal].pure[F]
          case Proximity.Absolute(_) => Option.empty[BigDecimal].pure[F]
        }
    }

  private def dispatchAll(
    alerts: List[Alert],
    policy: DeliveryPolicy,
    channels: List[String]
  ): F[Unit] =
    alerts.traverse(dispatcher.dispatch(_, policy, channels)).void

}

object AlertProcessImpl {

  /** Wires the alert process backed by the given bindings, rules, registry and dispatcher. */
  def create[I[_]: Sync, F[_]: Async: GenUUID](
    strategyBindings: StrategyBindingStorage[F],
    alertRules: AlertRuleStorage[F],
    registry: StrategyRegistry[F],
    liveStrategyService: LiveStrategyService[F],
    marketDataSources: MarketDataSources[F],
    priceHistory: PriceHistoryStorage[F],
    dispatcher: AlertDispatcher[F],
    paperBroker: PaperBroker[F],
    systemAlerts: SystemAlerts[F],
    config: AlertProcessConfig
  )(implicit logs: Logs[I, F], WL: WithLocal[F, ProcessContext]): I[AlertProcessImpl[F]] =
    for {
      previousQuotes <- Ref.in[I, F, Map[Ticker, Quote]](Map.empty)
      process <- logs
        .forService[AlertProcess[F]]
        .map(implicit log =>
          new AlertProcessImpl[F](
            strategyBindings,
            alertRules,
            registry,
            liveStrategyService,
            marketDataSources,
            priceHistory,
            dispatcher,
            paperBroker,
            systemAlerts,
            previousQuotes,
            config
          )
        )
    } yield process
}
