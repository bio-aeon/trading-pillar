package st.btu.trading.pillar.analytics.portfolio

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.analytics.{ConfiguredBars, FxRates, OperationHistoryYears}
import st.btu.trading.pillar.gateways.{InstrumentDirectory, MarketDataSources}
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.{
  AccountOperationStorage,
  InstrumentStorage,
  PriceHistoryStorage
}
import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.{LocalDate, ZoneOffset}

// Rules:
//  - Only executed operations are considered.
//  - Operations are loaded from `from - OperationHistoryYears` (not just `from`) so that positions, cash
//    and the cumulative `invested` figure are all complete at `from`, even when the account's
//    true history starts long before the requested window.
//  - Positions per instrument: Holdings of the executed operations, the sum of their signed
//    `quantity`, which is already in executed units, so a partly filled buy adds only what was
//    filled.
//  - cash = running sum of `amount` over *every* executed operation, kept per operation currency.
//  - external flows = the `amount` of deposits (+) and withdrawals (-), on their own date;
//    `invested` is their running sum, `cashFlow` is just that date's own sum.
//  - value(date) = cash + Sum(quantity * last known close on or before date), both converted to
//    `config.baseCurrency` where needed.
//  - An instrument is mapped to its ticker by the InstrumentDirectory. The ticker's bars come only
//    from the source the ticker is configured to use, never from another source as a fallback, and
//    so do the bars of an FX rate ticker.
//  - A position's price currency is the one InstrumentStorage holds for its ticker, when that was
//    read from the ticker's configured source, else the broker's currency for the instrument.
//  - A position's day return compares its last close on or before the date with the close of the
//    bar before it. A snapshot's day change sums quantity * (last close - previous close) over the
//    positions with both a value and a day return, converted at the rate their value used.
//  - A non-base cash currency or position with no FX rate is excluded from `value` and the
//    currency/ticker is recorded in `unpricedInstruments`; an instrument the directory does not
//    know is excluded and recorded in `unmappedInstruments`.
final class PortfolioValuationServiceImpl[F[_]] private (
  operations: AccountOperationStorage[F],
  directory: InstrumentDirectory[F],
  instruments: InstrumentStorage[F],
  sources: MarketDataSources[F],
  priceHistory: PriceHistoryStorage[F],
  config: ValuationConfig
)(implicit F: Monad[F])
    extends PortfolioValuationService[F] {

  import PortfolioValuationServiceImpl.*

  def dailySeries(accountId: AccountId, from: LocalDate, to: LocalDate): F[ValuationSeries] =
    if (to.isBefore(from))
      F.pure(ValuationSeries(accountId, config.baseCurrency, Vector.empty, Set.empty, Set.empty))
    else
      for {
        ops <- loadExecutedOperations(accountId, from, to)
        keys = positionInstruments(ops)
        held <- resolveInstruments(keys)
        tickerBars <- loadTickerBars(held.values, from, to)
        fxBars <- loadFxBars(neededCurrencies(ops, held), from, to)
        ctx = PricingContext(held, tickerBars, fxBars, config.baseCurrency)
        (points, unpriced) = buildSeries(from, to, ops, ctx)
      } yield ValuationSeries(accountId, config.baseCurrency, points, unpriced, keys -- held.keySet)

  // Same loading/pricing pipeline as dailySeries, narrowed to a single date: loadExecutedOperations
  // already loads everything up to and including `date` (OperationHistoryYears lookback, exclusive upper
  // bound at date + 1 day), so folding every returned operation into one CashState via applyOps
  // yields exactly the state as of `date` - there is no [from, to) range to walk day by day.
  def positionsAsOf(accountId: AccountId, date: LocalDate): F[PositionSnapshot] =
    for {
      ops <- loadExecutedOperations(accountId, date, date)
      held <- resolveInstruments(positionInstruments(ops))
      tickerBars <- loadTickerBars(held.values, date, date)
      fxBars <- loadFxBars(neededCurrencies(ops, held), date, date)
      ctx = PricingContext(held, tickerBars, fxBars, config.baseCurrency)
      state = applyOps(CashState.empty, ops.map(_._2))
      (cashBase, _) = convertBucket(state.cashByCurrency, date, ctx)
      priced = state.positions.iterator.map { case (instrument, qty) =>
        valueOnePosition(instrument, qty, date, ctx)
      }.toVector
      total = cashBase + priced.iterator.flatMap(_.position.value).sum
      positions = priced.map(_.position).map { p =>
        p.copy(weight = p.value.flatMap(weightOf(_, total)))
      }
    } yield PositionSnapshot(
      date,
      config.baseCurrency,
      positions.sortBy(_.instrument),
      cashBase,
      total,
      priced.iterator.flatMap(_.dayChange).sum,
      positions.count(_.value.isEmpty)
    )

  private def loadExecutedOperations(
    accountId: AccountId,
    from: LocalDate,
    to: LocalDate
  ): F[Vector[(LocalDate, AccountOperation)]] = {
    val since =
      from.minusYears(OperationHistoryYears.toLong).atStartOfDay(ZoneOffset.UTC).toInstant
    val until = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant
    operations
      .operations(accountId, since, until)
      .map(
        _.iterator
          .filter(_.state == OperationState.Executed)
          .map(op => op.occurredOn -> op)
          .toVector
      )
  }

  // The instruments an executed operation changed the position of at some point; every other
  // operation carries a zero quantity.
  private def positionInstruments(ops: Vector[(LocalDate, AccountOperation)]): Set[InstrumentKey] =
    ops.iterator.map(_._2).filter(_.quantity.signum != 0).flatMap(_.instrument).toSet

  private def resolveInstruments(keys: Set[InstrumentKey]): F[Map[InstrumentKey, HeldInstrument]] =
    keys.toVector
      .traverse(key => resolveInstrument(key).map(key -> _))
      .map(_.collect { case (key, Some(held)) => key -> held }.toMap)

  private def resolveInstrument(key: InstrumentKey): F[Option[HeldInstrument]] =
    directory.byKey(key).flatMap {
      case None => F.pure(None)
      case Some(broker) =>
        for {
          stored <- instruments.find(broker.ticker)
          source <- MarketDataSources.sourceOf(sources, broker.ticker)
        } yield Some(HeldInstrument.of(broker, source, stored))
    }

  private def neededCurrencies(
    ops: Vector[(LocalDate, AccountOperation)],
    held: Map[InstrumentKey, HeldInstrument]
  ): Set[Currency] = {
    val cashCurrencies = ops.iterator.map(_._2.currency).toSet
    val positionCurrencies = held.values.iterator.map(_.currency).toSet
    (cashCurrencies ++ positionCurrencies) - config.baseCurrency
  }

  // One load per ticker, although a ticker held on two boards has two instrument keys.
  private def loadTickerBars(
    held: Iterable[HeldInstrument],
    from: LocalDate,
    to: LocalDate
  ): F[Map[Ticker, Vector[Bar]]] = {
    val lookbackFrom = from.minusDays(config.priceLookback.toLong)
    held
      .map(h => h.ticker -> h.source)
      .toMap
      .toVector
      .traverse { case (ticker, source) =>
        ConfiguredBars.loadFrom(priceHistory, source, ticker, lookbackFrom, to).map(ticker -> _)
      }
      .map(_.toMap)
  }

  private def loadFxBars(
    currencies: Set[Currency],
    from: LocalDate,
    to: LocalDate
  ): F[Map[Currency, Vector[Bar]]] = {
    val lookbackFrom = from.minusDays(config.priceLookback.toLong)
    currencies.toVector
      .traverse { ccy =>
        FxRates
          .loadBars(
            priceHistory,
            sources,
            config.fxTickers,
            ccy,
            config.baseCurrency,
            lookbackFrom,
            to
          )
          .map(ccy -> _)
      }
      .map(_.toMap)
  }

  // Single pass over [from, to]: operations dated before `from` are folded into the initial
  // running state (so positions/cash/invested are complete at `from`); operations within the
  // window are grouped by their own date and applied as that date is reached.
  private def buildSeries(
    from: LocalDate,
    to: LocalDate,
    ops: Vector[(LocalDate, AccountOperation)],
    ctx: PricingContext
  ): (Vector[ValuationPoint], Set[String]) = {
    val (before, within) = ops.partition { case (date, _) => date.isBefore(from) }
    val withinByDate = within.groupBy(_._1)
    val initialState = applyOps(CashState.empty, before.map(_._2))
    val dates = Iterator.iterate(from)(_.plusDays(1)).takeWhile(!_.isAfter(to)).toVector

    val (_, points, unpriced) =
      dates.foldLeft((initialState, Vector.empty[ValuationPoint], Set.empty[String])) {
        case ((state, points, unpriced), date) =>
          val todaysOps = withinByDate.getOrElse(date, Vector.empty).map(_._2)
          val newState = applyOps(state, todaysOps)
          val todaysFlow =
            todaysOps.filter(isExternalFlow).groupMapReduce(_.currency)(_.amount)(_ + _)

          val (cashBase, cashUnpriced) = convertBucket(newState.cashByCurrency, date, ctx)
          val (investedBase, investedUnpriced) =
            convertBucket(newState.investedByCurrency, date, ctx)
          val (flowBase, flowUnpriced) = convertBucket(todaysFlow, date, ctx)
          val (positionsBase, posUnpriced) = valuePositions(newState.positions, date, ctx)

          val point = ValuationPoint(date, cashBase + positionsBase, investedBase, flowBase)
          (
            newState,
            points :+ point,
            unpriced ++ cashUnpriced ++ investedUnpriced ++ flowUnpriced ++ posUnpriced
          )
      }

    (points, unpriced)
  }

  private def applyOps(state: CashState, ops: Vector[AccountOperation]): CashState = {
    val withCash = ops.foldLeft(state) { (s, op) =>
      val currency = op.currency
      val cash2 =
        s.cashByCurrency.updated(currency, s.cashByCurrency.getOrElse(currency, Zero) + op.amount)
      val invested2 =
        if (isExternalFlow(op))
          s.investedByCurrency
            .updated(currency, s.investedByCurrency.getOrElse(currency, Zero) + op.amount)
        else s.investedByCurrency
      s.copy(cashByCurrency = cash2, investedByCurrency = invested2)
    }
    withCash.copy(positions = Holdings.after(state.positions, ops))
  }

  // Sums a per-currency bucket into the base currency; a currency other than the base with a
  // nonzero balance and no FX rate is dropped from the sum and reported in the unpriced set. A
  // zero balance is never reported (nothing was actually excluded).
  private def convertBucket(
    byCurrency: Map[Currency, BigDecimal],
    date: LocalDate,
    ctx: PricingContext
  ): (BigDecimal, Set[String]) =
    byCurrency.foldLeft((Zero, Set.empty[String])) { case ((sum, unpriced), (currency, amount)) =>
      if (currency == ctx.baseCurrency || amount == Zero) (sum + amount, unpriced)
      else
        fxRateOnOrBefore(currency, date, ctx) match {
          case Some(rate) => (sum + amount * rate, unpriced)
          case None => (sum, unpriced + currency.value)
        }
    }

  // Aggregate form used by dailySeries/buildSeries: sums every position's base-currency value,
  // and separately collects the tickers of *mapped* positions that couldn't be priced (an unmapped
  // instrument is already reported via unmappedInstruments, once per dailySeries call, not here).
  private def valuePositions(
    positions: Map[InstrumentKey, BigDecimal],
    date: LocalDate,
    ctx: PricingContext
  ): (BigDecimal, Set[String]) =
    positions.foldLeft((Zero, Set.empty[String])) { case ((sum, unpriced), (instrument, qty)) =>
      val priced = valueOnePosition(instrument, qty, date, ctx).position
      priced.value match {
        case Some(value) => (sum + value, unpriced)
        // Mapped but unpriced: report the ticker. Unmapped (ticker is also None): already
        // reported via unmappedInstruments, leave the unpriced set untouched.
        case None => (sum, priced.ticker.fold(unpriced)(t => unpriced + t.value))
      }
    }

  // Per-position pricing/FX logic shared by valuePositions (dailySeries) and positionsAsOf: prices
  // one instrument/quantity pair as of `date`. `price` is the last known close in the instrument's
  // own currency (unconverted); `value` is that price times `qty`, converted to ctx.baseCurrency -
  // None when the directory does not know the instrument, no close is known on or before `date`,
  // or (for a non-base currency) no FX rate is known on or before `date`. `dayReturn` compares
  // that close with the one before it, still in the instrument's own currency. `dayChange` is what
  // that move adds to `value`, converted at the rate `value` used, so it is set exactly when
  // `value` and `dayReturn` both are. `weight` is always None here; it depends on a snapshot-wide
  // total only positionsAsOf computes.
  private def valueOnePosition(
    instrument: InstrumentKey,
    qty: BigDecimal,
    date: LocalDate,
    ctx: PricingContext
  ): PricedPosition =
    ctx.held.get(instrument) match {
      case None =>
        PricedPosition(
          PositionValue(
            instrument,
            None,
            None,
            None,
            None,
            None,
            None,
            qty,
            None,
            None,
            None,
            None
          ),
          None
        )
      case Some(held) =>
        val closes = ctx.tickerBars.get(held.ticker).flatMap(latestCloses(_, date))
        def inBase(amount: BigDecimal): Option[BigDecimal] =
          toBase(amount, held.currency, date, ctx)
        PricedPosition(
          PositionValue(
            instrument,
            Some(held.ticker),
            Some(held.name),
            Some(held.instrumentType),
            Some(held.currency),
            held.exchange,
            Some(held.source),
            qty,
            closes.map(_.last),
            closes.flatMap(c => inBase(c.last * qty)),
            None,
            closes.flatMap(_.dayReturn)
          ),
          closes.flatMap(_.priceChange).flatMap(change => inBase(change * qty))
        )
    }

  private def weightOf(value: BigDecimal, total: BigDecimal): Option[BigDecimal] =
    if (total == Zero) None else Some(value / total)

  // `amount` of `currency` in ctx.baseCurrency, at the latest rate on or before `date`; None when
  // `currency` has no rate.
  private def toBase(
    amount: BigDecimal,
    currency: Currency,
    date: LocalDate,
    ctx: PricingContext
  ): Option[BigDecimal] =
    if (currency == ctx.baseCurrency) Some(amount)
    else fxRateOnOrBefore(currency, date, ctx).map(amount * _)

  private def fxRateOnOrBefore(
    currency: Currency,
    date: LocalDate,
    ctx: PricingContext
  ): Option[BigDecimal] =
    ctx.fxBars.get(currency).flatMap(FxRates.closeOnOrBefore(_, date))

  // Money paid into the account from outside, or taken out of it: what `invested` sums.
  private def isExternalFlow(op: AccountOperation): Boolean =
    op.kind match {
      case OperationKind.Deposit | OperationKind.Withdrawal => true
      case OperationKind.Buy | OperationKind.Sell | OperationKind.SecurityIn |
          OperationKind.SecurityOut | OperationKind.Dividend | OperationKind.Coupon |
          OperationKind.Interest | OperationKind.Fee | OperationKind.Tax | OperationKind.Exchange |
          OperationKind.Other =>
        false
    }
}

object PortfolioValuationServiceImpl {

  private val Zero = BigDecimal(0)

  // Running, not-yet-base-converted state as of some date: cash and cumulative external flow, per
  // operation currency, plus the open position per instrument, as Holdings gives them.
  private final case class CashState(
    cashByCurrency: Map[Currency, BigDecimal],
    investedByCurrency: Map[Currency, BigDecimal],
    positions: Map[InstrumentKey, BigDecimal]
  )

  private object CashState {
    val empty: CashState = CashState(Map.empty, Map.empty, Map.empty)
  }

  private final class HeldInstrument private (
    broker: BrokerInstrument,
    val source: Source,
    info: Option[InstrumentInfo]
  ) {
    def ticker: Ticker = broker.ticker
    def currency: Currency = info.fold(broker.currency)(_.currency)
    def name: String = info.flatMap(_.name).getOrElse(broker.name)
    def instrumentType: String = info.fold(broker.instrumentType)(_.instrumentType)
    def exchange: Option[String] = info.map(_.exchange)
  }

  private object HeldInstrument {

    // Metadata read from another source describes the instrument as that source does, in another
    // currency perhaps, so it counts as missing.
    def of(
      broker: BrokerInstrument,
      source: Source,
      stored: Option[InstrumentInfo]
    ): HeldInstrument =
      new HeldInstrument(broker, source, stored.filter(_.origin.source == source))
  }

  private final case class PricingContext(
    held: Map[InstrumentKey, HeldInstrument],
    tickerBars: Map[Ticker, Vector[Bar]],
    fxBars: Map[Currency, Vector[Bar]],
    baseCurrency: Currency
  )

  // A position priced as of a date, with what its latest price move added to its value in the base
  // currency; None when the position has no value or no day return.
  private final case class PricedPosition(position: PositionValue, dayChange: Option[BigDecimal])

  // The last close on or before a date and, when there is one and it is positive, the close of the
  // bar before it: a return from a zero close is undefined.
  private final case class LatestCloses(last: BigDecimal, previous: Option[BigDecimal]) {
    def dayReturn: Option[BigDecimal] = previous.map(divide(last, _) - 1)
    def priceChange: Option[BigDecimal] = previous.map(last - _)
  }

  // `bars` must be chronological, as ConfiguredBars returns them.
  private def latestCloses(bars: Vector[Bar], date: LocalDate): Option[LatestCloses] =
    bars.reverseIterator.filterNot(_.date.isAfter(date)).take(2).map(_.close).toList match {
      case last :: rest => Some(LatestCloses(last, rest.headOption.filter(_ > 0)))
      case Nil => None
    }

  /** Wires a `PortfolioValuationService` over the executed operations in `operations`, that maps
    * instruments through `directory`, takes prices from `priceHistory` as `sources` configures
    * them, and reads price currencies from `instruments`.
    */
  def create[F[_]: Monad](
    operations: AccountOperationStorage[F],
    directory: InstrumentDirectory[F],
    instruments: InstrumentStorage[F],
    sources: MarketDataSources[F],
    priceHistory: PriceHistoryStorage[F],
    config: ValuationConfig
  ): PortfolioValuationService[F] =
    new PortfolioValuationServiceImpl[F](
      operations,
      directory,
      instruments,
      sources,
      priceHistory,
      config
    )
}
