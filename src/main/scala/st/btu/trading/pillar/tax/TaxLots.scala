package st.btu.trading.pillar.tax

import st.btu.trading.pillar.models.domain.{
  Figi,
  OperationStatus,
  OperationType,
  TcsPortfolioOperation
}
import st.btu.trading.pillar.models.market.Ticker

import java.time.temporal.ChronoUnit
import java.time.{LocalDate, ZoneOffset}
import scala.math.BigDecimal.RoundingMode

/** Pure FIFO cost-basis engine over an account's executed operations. One `Lot` is opened per buy
  * or security-transfer-in operation; a sell consumes the oldest open lots first, partially when
  * the sell falls inside a lot, and always produces exactly one `RealizedGain`.
  */
object TaxLots {

  private val Zero = BigDecimal(0)

  /** Matches `operations` into open lots and realized gains, FIFO per instrument.
    *
    * @param tickerOf
    *   resolves a figi to its ticker, for reporting only
    */
  def fifo(operations: List[TcsPortfolioOperation], tickerOf: Figi => Option[Ticker]): LotLedger = {
    val executed = operations.filter(_.statusEnum == OperationStatus.Executed)
    val withFigi = executed.filter(_.figi.isDefined)
    val byFigi: Map[Figi, List[TcsPortfolioOperation]] = withFigi.groupBy(_.figi.get)

    // Each day's total OPERATION_TYPE_BROKER_FEE for a figi, pro-rated separately into that same
    // day's buys (added to cost) and sells (subtracted from proceeds) - see `processFigi`. A
    // single day's fee is used in full for each side independently; this is a documented
    // simplification (docs/tax.md), not a claim that a day never mixes a buy's and a sell's own
    // separate commissions.
    val feesByFigiDate: Map[(Figi, LocalDate), BigDecimal] =
      withFigi
        .filter(_.operationTypeEnum == OperationType.BrokerFee)
        .groupMapReduce(op => (op.figi.get, dateOf(op)))(_.amount.abs)(_ + _)

    val perFigi = byFigi.map { case (figi, ops) =>
      figi -> processFigi(figi, ops, feesByFigiDate, tickerOf)
    }

    val open = perFigi.collect { case (figi, r) if r.open.nonEmpty => figi -> r.open }.toMap
    val unmatched = perFigi.collect {
      case (figi, r) if r.unmatched > 0 => figi -> r.unmatched
    }.toMap
    val realized = perFigi.iterator
      .flatMap(_._2.realized)
      .toList
      .sortBy(rg => (rg.sellDate.toEpochDay, rg.figi.value))

    LotLedger(open, realized, unmatched)
  }

  private def dateOf(op: TcsPortfolioOperation): LocalDate =
    op.createdAt.withZoneSameInstant(ZoneOffset.UTC).toLocalDate

  private final case class Consumed(lot: Lot, quantity: Long)

  private final case class FigiResult(
    open: List[Lot],
    realized: List[RealizedGain],
    unmatched: Long
  )

  private def processFigi(
    figi: Figi,
    ops: List[TcsPortfolioOperation],
    feesByFigiDate: Map[(Figi, LocalDate), BigDecimal],
    tickerOf: Figi => Option[Ticker]
  ): FigiResult = {
    // Stable chronological order within this figi: operation instant, then the storage's own
    // surrogate id as a tiebreaker for same-instant operations.
    val sorted = ops.sortBy(op => (op.createdAt.toInstant.toEpochMilli, op.id.value))
    val ticker = tickerOf(figi)

    val buyQtyByDate = quantityByDate(sorted, _.isBuy)
    val sellQtyByDate = quantityByDate(sorted, _.isSell)
    def feeFor(date: LocalDate): BigDecimal = feesByFigiDate.getOrElse((figi, date), Zero)

    val result = sorted.foldLeft(FigiResult(Nil, Nil, 0L)) { (acc, op) =>
      val opType = op.operationTypeEnum
      val date = dateOf(op)
      val qty = op.quantity.toLong

      if (opType.isBuy) {
        val feeShare = proRated(feeFor(date), qty, buyQtyByDate.getOrElse(date, 0L))
        val unitCost = if (qty == 0L) Zero else (op.amount.abs + feeShare) / qty
        acc.copy(open = acc.open :+ Lot(figi, qty, unitCost, date))
      } else if (opType == OperationType.InputSecurities) {
        acc.copy(open = acc.open :+ Lot(figi, qty, op.price, date))
      } else if (opType.isSell) {
        val feeShare = proRated(feeFor(date), qty, sellQtyByDate.getOrElse(date, 0L))
        val proceeds = op.amount.abs - feeShare
        val (consumed, remainingOpen) = consume(acc.open, qty)
        val matchedQty = consumed.iterator.map(_.quantity).sum
        val costBasis = consumed.iterator.map(c => c.lot.unitCost * c.quantity).sum
        val holdingDays = holdingDaysOf(consumed, date, matchedQty)
        val gain = proceeds - costBasis
        val realizedGain =
          RealizedGain(figi, ticker, date, matchedQty, proceeds, costBasis, gain, holdingDays)
        acc.copy(
          open = remainingOpen,
          realized = realizedGain :: acc.realized,
          unmatched = acc.unmatched + (qty - matchedQty)
        )
      } else acc
    }

    result.copy(realized = result.realized.reverse)
  }

  private def quantityByDate(
    ops: List[TcsPortfolioOperation],
    matches: OperationType => Boolean
  ): Map[LocalDate, Long] =
    ops
      .filter(op => matches(op.operationTypeEnum))
      .groupMapReduce(dateOf)(_.quantity.toLong)(_ + _)

  private def proRated(total: BigDecimal, share: Long, ofTotal: Long): BigDecimal =
    if (ofTotal <= 0L) Zero else total * share / ofTotal

  // Quantity-weighted average of the consumed lots' own holding periods, rounded to the nearest
  // whole day - see RealizedGain's doc comment. `0` when nothing was matched (nothing to average).
  private def holdingDaysOf(consumed: List[Consumed], sellDate: LocalDate, matchedQty: Long): Long =
    if (matchedQty <= 0L) 0L
    else {
      val weightedDaysSum =
        consumed.iterator
          .map(c => c.quantity * ChronoUnit.DAYS.between(c.lot.acquiredOn, sellDate))
          .sum
      (BigDecimal(weightedDaysSum) / matchedQty).setScale(0, RoundingMode.HALF_UP).toLongExact
    }

  // Takes up to `qty` shares from the front of `lots` (oldest first, i.e. FIFO), splitting the one
  // lot that only partially covers the remainder. When `lots` is exhausted before `qty` is
  // satisfied, whatever was available is returned as consumed; the caller derives the unmatched
  // shortfall as `qty - consumed.map(_.quantity).sum`.
  private def consume(lots: List[Lot], qty: Long): (List[Consumed], List[Lot]) = {
    @annotation.tailrec
    def go(
      remaining: List[Lot],
      toConsume: Long,
      acc: List[Consumed]
    ): (List[Consumed], List[Lot]) =
      remaining match {
        case lot :: rest if toConsume > 0 && lot.quantity <= toConsume =>
          go(rest, toConsume - lot.quantity, Consumed(lot, lot.quantity) :: acc)
        case lot :: rest if toConsume > 0 =>
          val piece = Consumed(lot, toConsume)
          val leftover = lot.copy(quantity = lot.quantity - toConsume)
          ((piece :: acc).reverse, leftover :: rest)
        case _ => (acc.reverse, remaining)
      }
    go(lots, qty, Nil)
  }
}
