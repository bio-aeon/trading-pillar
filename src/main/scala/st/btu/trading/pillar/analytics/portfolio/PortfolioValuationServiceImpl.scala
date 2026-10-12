package st.btu.trading.pillar.analytics.portfolio

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.analytics.{FxRates, OperationHistoryYears}
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.{Bar, InstrumentInfo, Source, Ticker}
import st.btu.trading.pillar.storages.{
  InstrumentStorage,
  PriceHistoryStorage,
  TcsPortfolioOperationStorage
}

import java.time.{LocalDate, ZoneOffset}

// Rules:
//  - Only OPERATION_STATE_EXECUTED operations are considered.
//  - Operations are loaded from `from - OperationHistoryYears` (not just `from`) so that positions, cash
//    and the cumulative `invested` figure are all complete at `from`, even when the account's
//    true history starts long before the requested window.
//  - Positions per FIGI: a buy-like operation (OperationType.isBuy, or a securities transfer in)
//    adds `quantity`, a sell-like one (isSell, or a transfer out) subtracts it.
//  - cash = running sum of `amount` over *every* executed operation, kept per operation currency.
//  - external flows = OPERATION_TYPE_INPUT (+) / OPERATION_TYPE_OUTPUT (-) `amount`, on their own
//    date; `invested` is their running sum, `cashFlow` is just that date's own sum.
//  - value(date) = cash + Sum(quantity * last known close on or before date), both converted to
//    `config.baseCurrency` where needed.
//  - A non-base cash currency or position with no FX rate is excluded from `value` and the
//    currency/ticker is recorded in `unpricedInstruments`; a FIGI with no InstrumentStorage
//    mapping is excluded and recorded in `unmappedFigis`.
final class PortfolioValuationServiceImpl[F[_]] private (
  operations: TcsPortfolioOperationStorage[F],
  instruments: InstrumentStorage[F],
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
        figis = positionFigis(ops)
        figiToInfo <- resolveInstruments(figis)
        tickerBars <- loadTickerBars(figiToInfo.values.map(_.ticker).toSet, from, to)
        fxBars <- loadFxBars(neededCurrencies(ops, figiToInfo), from, to)
        ctx = PricingContext(figiToInfo, tickerBars, fxBars, config.baseCurrency)
        (points, unpriced) = buildSeries(from, to, ops, ctx)
      } yield ValuationSeries(
        accountId,
        config.baseCurrency,
        points,
        unpriced,
        (figis -- figiToInfo.keySet).map(_.value)
      )

  // Same loading/pricing pipeline as dailySeries, narrowed to a single date: loadExecutedOperations
  // already loads everything up to and including `date` (OperationHistoryYears lookback, exclusive upper
  // bound at date + 1 day), so folding every returned operation into one CashState via applyOps
  // yields exactly the state as of `date` - there is no [from, to) range to walk day by day.
  def positionsAsOf(accountId: AccountId, date: LocalDate): F[PositionSnapshot] =
    for {
      ops <- loadExecutedOperations(accountId, date, date)
      figiToInfo <- resolveInstruments(positionFigis(ops))
      tickerBars <- loadTickerBars(figiToInfo.values.map(_.ticker).toSet, date, date)
      fxBars <- loadFxBars(neededCurrencies(ops, figiToInfo), date, date)
      ctx = PricingContext(figiToInfo, tickerBars, fxBars, config.baseCurrency)
      state = applyOps(CashState.empty, ops.map(_._2))
      (cashBase, _) = convertBucket(state.cashByCurrency, date, ctx)
      priced = state.positions.iterator.collect {
        case (figi, qty) if qty != 0 => valueOnePosition(figi, qty, date, ctx)
      }.toVector
      total = cashBase + priced.iterator.flatMap(_.value).sum
      positions = priced.map(p => p.copy(weight = p.value.flatMap(weightOf(_, total))))
    } yield PositionSnapshot(
      date,
      config.baseCurrency,
      positions.sortBy(_.figi.value),
      cashBase,
      total,
      positions.count(_.value.isEmpty)
    )

  private def loadExecutedOperations(
    accountId: AccountId,
    from: LocalDate,
    to: LocalDate
  ): F[Vector[(LocalDate, TcsPortfolioOperation)]] = {
    val since = from.minusYears(OperationHistoryYears.toLong).atStartOfDay(ZoneOffset.UTC)
    val until = to.plusDays(1).atStartOfDay(ZoneOffset.UTC)
    operations
      .operationsByDtRange(accountId, since, until)
      .map(
        _.iterator
          .filter(isExecuted)
          .map(op => op.createdAt.withZoneSameInstant(ZoneOffset.UTC).toLocalDate -> op)
          .toVector
          .sortBy { case (date, op) => (date.toEpochDay, op.createdAt.toInstant.toEpochMilli) }
      )
  }

  private def positionFigis(ops: Vector[(LocalDate, TcsPortfolioOperation)]): Set[Figi] =
    ops.iterator.map(_._2).filter(op => isBuy(op) || isSell(op)).flatMap(_.figi).toSet

  private def resolveInstruments(figis: Set[Figi]): F[Map[Figi, InstrumentInfo]] =
    figis.toVector
      .traverse(figi => instruments.findByFigi(figi.value).map(figi -> _))
      .map(_.collect { case (figi, Some(info)) => figi -> info }.toMap)

  private def neededCurrencies(
    ops: Vector[(LocalDate, TcsPortfolioOperation)],
    figiToInfo: Map[Figi, InstrumentInfo]
  ): Set[String] = {
    val cashCurrencies = ops.iterator.map(_._2.currency.value).toSet
    val positionCurrencies = figiToInfo.values.iterator.map(_.currency).toSet
    (cashCurrencies ++ positionCurrencies) - config.baseCurrency
  }

  private def loadTickerBars(
    tickers: Set[Ticker],
    from: LocalDate,
    to: LocalDate
  ): F[Map[Ticker, Vector[Bar]]] = {
    val lookbackFrom = from.minusDays(config.priceLookback.toLong)
    tickers.toVector.traverse(t => barsFor(t, lookbackFrom, to).map(t -> _)).map(_.toMap)
  }

  // Yahoo, then Tinkoff, whichever has bars for this ticker. InstrumentInfo carries no explicit
  // `source` field today, so there is nothing to resolve "from the instrument" yet; this fallback
  // chain is the whole of that rule for now.
  private def barsFor(ticker: Ticker, from: LocalDate, to: LocalDate): F[Vector[Bar]] =
    priceHistory.bars(ticker, Source.Yahoo, from, to).flatMap { yahoo =>
      if (yahoo.nonEmpty) F.pure(sortedByDate(yahoo))
      else priceHistory.bars(ticker, Source.Tinkoff, from, to).map(sortedByDate)
    }

  private def loadFxBars(
    currencies: Set[String],
    from: LocalDate,
    to: LocalDate
  ): F[Map[String, Vector[Bar]]] = {
    val lookbackFrom = from.minusDays(config.priceLookback.toLong)
    currencies.toVector
      .traverse { ccy =>
        FxRates
          .loadBars(priceHistory, ccy, config.baseCurrency, config.fxSource, lookbackFrom, to)
          .map(ccy -> _)
      }
      .map(_.toMap)
  }

  private def sortedByDate(bars: List[Bar]): Vector[Bar] = bars.toVector.sortBy(_.date.toEpochDay)

  // Single pass over [from, to]: operations dated before `from` are folded into the initial
  // running state (so positions/cash/invested are complete at `from`); operations within the
  // window are grouped by their own date and applied as that date is reached.
  private def buildSeries(
    from: LocalDate,
    to: LocalDate,
    ops: Vector[(LocalDate, TcsPortfolioOperation)],
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
            todaysOps.filter(isFlow).groupMapReduce(_.currency.value)(_.amount)(_ + _)

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

  private def applyOps(state: CashState, ops: Vector[TcsPortfolioOperation]): CashState =
    ops.foldLeft(state) { (s, op) =>
      val currency = op.currency.value
      val cash2 =
        s.cashByCurrency.updated(currency, s.cashByCurrency.getOrElse(currency, Zero) + op.amount)
      val invested2 =
        if (isFlow(op))
          s.investedByCurrency
            .updated(currency, s.investedByCurrency.getOrElse(currency, Zero) + op.amount)
        else s.investedByCurrency
      val positions2 = op.figi match {
        case Some(figi) if isBuy(op) =>
          s.positions.updated(figi, s.positions.getOrElse(figi, 0) + op.quantity)
        case Some(figi) if isSell(op) =>
          s.positions.updated(figi, s.positions.getOrElse(figi, 0) - op.quantity)
        case _ => s.positions
      }
      CashState(cash2, invested2, positions2)
    }

  // Sums a per-currency bucket into the base currency; a currency other than the base with a
  // nonzero balance and no FX rate is dropped from the sum and reported in the unpriced set. A
  // zero balance is never reported (nothing was actually excluded).
  private def convertBucket(
    byCurrency: Map[String, BigDecimal],
    date: LocalDate,
    ctx: PricingContext
  ): (BigDecimal, Set[String]) =
    byCurrency.foldLeft((Zero, Set.empty[String])) { case ((sum, unpriced), (currency, amount)) =>
      if (currency == ctx.baseCurrency || amount == Zero) (sum + amount, unpriced)
      else
        fxRateOnOrBefore(currency, date, ctx) match {
          case Some(rate) => (sum + amount * rate, unpriced)
          case None => (sum, unpriced + currency)
        }
    }

  // Aggregate form used by dailySeries/buildSeries: sums every nonzero position's base-currency
  // value, and separately collects the tickers of *mapped* positions that couldn't be priced (an
  // unmapped figi is already reported via unmappedFigis, once per dailySeries call, not here).
  private def valuePositions(
    positions: Map[Figi, Int],
    date: LocalDate,
    ctx: PricingContext
  ): (BigDecimal, Set[String]) =
    positions.foldLeft((Zero, Set.empty[String])) { case ((sum, unpriced), (figi, qty)) =>
      if (qty == 0) (sum, unpriced)
      else {
        val priced = valueOnePosition(figi, qty, date, ctx)
        priced.value match {
          case Some(value) => (sum + value, unpriced)
          // Mapped but unpriced: report the ticker. Unmapped (ticker is also None): already
          // reported via unmappedFigis, leave the unpriced set untouched.
          case None => (sum, priced.ticker.fold(unpriced)(t => unpriced + t.value))
        }
      }
    }

  // Per-position pricing/FX logic shared by valuePositions (dailySeries) and positionsAsOf: prices
  // one figi/quantity pair as of `date`. `price` is the last known close in the instrument's own
  // currency (unconverted); `value` is that price times `qty`, converted to ctx.baseCurrency - None
  // when the figi has no InstrumentStorage mapping, no known close on or before `date`, or (for a
  // non-base currency) no FX rate on or before `date`. `weight` is always None here; it depends on
  // a snapshot-wide total only positionsAsOf computes.
  private def valueOnePosition(
    figi: Figi,
    qty: Int,
    date: LocalDate,
    ctx: PricingContext
  ): PositionValue =
    ctx.figiToInfo.get(figi) match {
      case None => PositionValue(figi, None, None, None, None, None, qty, None, None, None)
      case Some(info) =>
        val price = ctx.tickerBars.get(info.ticker).flatMap(FxRates.closeOnOrBefore(_, date))
        val value = price.flatMap { close =>
          val raw = close * qty
          if (info.currency == ctx.baseCurrency) Some(raw)
          else fxRateOnOrBefore(info.currency, date, ctx).map(raw * _)
        }
        PositionValue(
          figi,
          Some(info.ticker),
          info.name,
          Some(info.instrumentType),
          Some(info.currency),
          Some(info.exchange),
          qty,
          price,
          value,
          None
        )
    }

  private def weightOf(value: BigDecimal, total: BigDecimal): Option[BigDecimal] =
    if (total == Zero) None else Some(value / total)

  private def fxRateOnOrBefore(
    currency: String,
    date: LocalDate,
    ctx: PricingContext
  ): Option[BigDecimal] =
    ctx.fxBars.get(currency).flatMap(FxRates.closeOnOrBefore(_, date))

  private def isExecuted(op: TcsPortfolioOperation): Boolean =
    op.statusEnum == OperationStatus.Executed

  // Buy-like/sell-like for position-quantity purposes: a straightforward buy/sell, or a
  // securities transfer in/out, which moves quantity the same way without any cash amount - see
  // TaxLots.fifo's own INPUT_SECURITIES handling for the ledger's equivalent rule.
  private def isBuy(op: TcsPortfolioOperation): Boolean =
    op.operationTypeEnum.isBuy || op.operationTypeEnum == OperationType.InputSecurities

  private def isSell(op: TcsPortfolioOperation): Boolean =
    op.operationTypeEnum.isSell || op.operationTypeEnum == OperationType.OutputSecurities

  private def isFlow(op: TcsPortfolioOperation): Boolean = op.operationTypeEnum.isExternalFlow
}

object PortfolioValuationServiceImpl {

  private val Zero = BigDecimal(0)

  // Running, not-yet-base-converted state as of some date: cash and cumulative external flow, per
  // operation currency, plus the cumulative position per FIGI.
  private final case class CashState(
    cashByCurrency: Map[String, BigDecimal],
    investedByCurrency: Map[String, BigDecimal],
    positions: Map[Figi, Int]
  )

  private object CashState {
    val empty: CashState = CashState(Map.empty, Map.empty, Map.empty)
  }

  private final case class PricingContext(
    figiToInfo: Map[Figi, InstrumentInfo],
    tickerBars: Map[Ticker, Vector[Bar]],
    fxBars: Map[String, Vector[Bar]],
    baseCurrency: String
  )

  /** Wires a `PortfolioValuationService` backed by the given storages and `config`. */
  def create[F[_]: Monad](
    operations: TcsPortfolioOperationStorage[F],
    instruments: InstrumentStorage[F],
    priceHistory: PriceHistoryStorage[F],
    config: ValuationConfig
  ): PortfolioValuationService[F] =
    new PortfolioValuationServiceImpl[F](operations, instruments, priceHistory, config)
}
