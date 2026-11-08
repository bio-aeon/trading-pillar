package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.{
  AccountOperation,
  InstrumentKey,
  OperationKind,
  OperationState
}
import st.btu.trading.pillar.models.market.Ticker

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.math.BigDecimal.RoundingMode

/** Pure FIFO cost-basis engine over an account's executed operations. One `Lot` is opened per buy
  * or security-transfer-in operation that added units; a sell consumes the oldest open lots first,
  * partially when the sell falls inside a lot, and always produces exactly one `RealizedGain`.
  */
object TaxLots {

  private val Zero = BigDecimal(0)

  /** Matches `operations` into open lots and realized gains, FIFO per instrument.
    *
    * @param tickerOf
    *   resolves an instrument to its ticker, for reporting only
    */
  def fifo(
    operations: List[AccountOperation],
    tickerOf: InstrumentKey => Option[Ticker]
  ): LotLedger = {
    val byInstrument: Map[InstrumentKey, List[AccountOperation]] =
      operations
        .filter(_.state == OperationState.Executed)
        .flatMap(op => op.instrument.map(_ -> op))
        .groupMap { case (instrument, _) => instrument } { case (_, op) => op }

    val perInstrument = byInstrument.map { case (instrument, ops) =>
      instrument -> processInstrument(instrument, ops, tickerOf(instrument))
    }

    val open = perInstrument.collect {
      case (instrument, result) if result.open.nonEmpty => instrument -> result.open
    }
    val unmatched = perInstrument.collect {
      case (instrument, result) if result.unmatched > Zero => instrument -> result.unmatched
    }
    val realized = perInstrument.valuesIterator
      .flatMap(_.realized)
      .toList
      .sortBy(rg => (rg.sellDate.toEpochDay, rg.instrument))

    LotLedger(open, realized, unmatched)
  }

  private final case class Consumed(lot: Lot, quantity: BigDecimal)

  private final case class InstrumentResult(
    open: List[Lot],
    realized: List[RealizedGain],
    unmatched: BigDecimal
  )

  private def processInstrument(
    instrument: InstrumentKey,
    ops: List[AccountOperation],
    ticker: Option[Ticker]
  ): InstrumentResult = {
    // Stable chronological order within this instrument: the operation's instant, then its ref as
    // a tiebreaker for same-instant operations.
    val sorted = ops.sortBy(op => (op.occurredAt, op.ref.value))

    // Each day's total fee for the instrument, pro-rated separately into that same day's buys
    // (added to cost) and sells (subtracted from proceeds). A single day's fee is used in full for
    // each side independently; this is a documented simplification (docs/tax.md), not a claim
    // that a day never mixes a buy's and a sell's own separate commissions.
    val feesByDate: Map[LocalDate, BigDecimal] =
      sorted.filter(_.kind == OperationKind.Fee).groupMapReduce(_.occurredOn)(_.amount.abs)(_ + _)
    val buyQtyByDate = quantityByDate(sorted, OperationKind.Buy)
    val sellQtyByDate = quantityByDate(sorted, OperationKind.Sell)
    def feeFor(date: LocalDate): BigDecimal = feesByDate.getOrElse(date, Zero)

    val result = sorted.foldLeft(InstrumentResult(Nil, Nil, Zero)) { (acc, op) =>
      val date = op.occurredOn
      op.kind match {
        case OperationKind.Buy =>
          val feeShare = proRated(feeFor(date), op.quantity, buyQtyByDate.getOrElse(date, Zero))
          val unitCost =
            if (op.quantity.signum == 0) Zero else (op.amount.abs + feeShare) / op.quantity
          withLot(acc, Lot(instrument, op.quantity, unitCost, date))
        case OperationKind.SecurityIn =>
          withLot(acc, Lot(instrument, op.quantity, op.price.getOrElse(Zero), date))
        case OperationKind.Sell =>
          val sold = op.quantity.abs
          val feeShare = proRated(feeFor(date), sold, sellQtyByDate.getOrElse(date, Zero))
          val proceeds = op.amount.abs - feeShare
          val (consumed, remainingOpen) = consume(acc.open, sold)
          val matchedQty = consumed.iterator.map(_.quantity).sum
          val costBasis = consumed.iterator.map(c => c.lot.unitCost * c.quantity).sum
          val holdingDays = holdingDaysOf(consumed, date, matchedQty)
          val gain = proceeds - costBasis
          val realizedGain = RealizedGain(
            instrument,
            ticker,
            date,
            matchedQty,
            proceeds,
            costBasis,
            gain,
            holdingDays
          )
          acc.copy(
            open = remainingOpen,
            realized = realizedGain :: acc.realized,
            unmatched = acc.unmatched + (sold - matchedQty)
          )
        case OperationKind.SecurityOut | OperationKind.Deposit | OperationKind.Withdrawal |
            OperationKind.Dividend | OperationKind.Coupon | OperationKind.Interest |
            OperationKind.Fee | OperationKind.Tax | OperationKind.Other =>
          acc
      }
    }
    result.copy(realized = result.realized.reverse)
  }

  // A lot of no units, such as a buy that executed none, is not a lot.
  private def withLot(acc: InstrumentResult, lot: Lot): InstrumentResult =
    if (lot.quantity.signum > 0) acc.copy(open = acc.open :+ lot) else acc

  private def quantityByDate(
    ops: List[AccountOperation],
    kind: OperationKind
  ): Map[LocalDate, BigDecimal] =
    ops.filter(_.kind == kind).groupMapReduce(_.occurredOn)(_.quantity.abs)(_ + _)

  private def proRated(total: BigDecimal, share: BigDecimal, ofTotal: BigDecimal): BigDecimal =
    if (ofTotal <= Zero) Zero else total * share / ofTotal

  // Quantity-weighted average of the consumed lots' own holding periods, rounded to the nearest
  // whole day - see RealizedGain's doc comment. `0` when nothing was matched (nothing to average).
  private def holdingDaysOf(
    consumed: List[Consumed],
    sellDate: LocalDate,
    matchedQty: BigDecimal
  ): Long =
    if (matchedQty <= Zero) 0L
    else {
      val weightedDaysSum =
        consumed.iterator
          .map(c => c.quantity * BigDecimal(ChronoUnit.DAYS.between(c.lot.acquiredOn, sellDate)))
          .sum
      (weightedDaysSum / matchedQty).setScale(0, RoundingMode.HALF_UP).toLongExact
    }

  // Takes up to `qty` units from the front of `lots` (oldest first, i.e. FIFO), splitting the one
  // lot that only partially covers the remainder. When `lots` is exhausted before `qty` is
  // satisfied, whatever was available is returned as consumed; the caller derives the unmatched
  // shortfall as `qty - consumed.map(_.quantity).sum`.
  private def consume(lots: List[Lot], qty: BigDecimal): (List[Consumed], List[Lot]) = {
    @annotation.tailrec
    def go(
      remaining: List[Lot],
      toConsume: BigDecimal,
      acc: List[Consumed]
    ): (List[Consumed], List[Lot]) =
      remaining match {
        case lot :: rest if toConsume > Zero && lot.quantity <= toConsume =>
          go(rest, toConsume - lot.quantity, Consumed(lot, lot.quantity) :: acc)
        case lot :: rest if toConsume > Zero =>
          val piece = Consumed(lot, toConsume)
          val leftover = lot.copy(quantity = lot.quantity - toConsume)
          ((piece :: acc).reverse, leftover :: rest)
        case _ => (acc.reverse, remaining)
      }
    go(lots, qty, Nil)
  }
}
