package st.btu.trading.pillar.tax

import st.btu.trading.pillar.analytics.Proportions
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.math.BigDecimal.RoundingMode

/** Pure FIFO cost-basis engine over an account's executed operations. One `Lot` is opened per buy
  * or security-transfer-in operation that added units; a sell consumes the oldest open lots first,
  * partially when the sell falls inside a lot, and always produces exactly one `RealizedGain`. A
  * security transfer out consumes the oldest open lots the same way but realizes nothing: the units
  * left the account at their cost, and a transfer is not a disposal.
  *
  * Corporate actions carry the lots through: a split scales the lots of its instrument, a
  * conversion moves the lots of what was given up to what was received, a spin-off gives the new
  * instrument lots out of the parent's, and a stock dividend opens a lot at its market value. Lots
  * keep their acquisition dates through all of them.
  */
object TaxLots {

  private val Zero = BigDecimal(0)

  // The kinds whose operations of one group take effect together, at the first of them.
  private val AppliedTogether: Set[OperationKind] =
    Set(OperationKind.Conversion, OperationKind.Spinoff)

  /** Matches `operations` into open lots and realized gains, FIFO per instrument, in one
    * chronological pass over every instrument of the account, so that a corporate action can move
    * lots from one instrument to another.
    *
    * @param tickerOf
    *   resolves an instrument to its ticker, for reporting only
    * @param allocations
    *   the share of a spin-off parent's cost that moves to each new instrument; a spin-off with no
    *   allocation for an instrument gives it none of the cost
    */
  def fifo(
    operations: List[AccountOperation],
    tickerOf: InstrumentKey => Option[Ticker],
    allocations: List[CorporateActionAllocation] = Nil
  ): LotLedger = {
    // One chronological order for the whole account: the operation's instant, then its ref as a
    // tiebreaker for same-instant operations.
    val ordered = operations
      .filter(_.state == OperationState.Executed)
      .flatMap(op => op.instrument.map(OnInstrument(_, op)))
      .sortBy(entry => (entry.operation.occurredAt, entry.operation.ref.value))

    val fold = new Fold(FeePools.of(ordered), tickerOf, allocations)
    val end = stepsOf(ordered).foldLeft(State.empty)(fold(_, _))

    LotLedger(
      end.open,
      end.realized.reverse.sortBy(rg => (rg.sellDate.toEpochDay, rg.instrument)),
      end.unmatched.filter { case (_, quantity) => quantity > Zero }
    )
  }

  // An executed operation with the instrument it names.
  private final case class OnInstrument(instrument: InstrumentKey, operation: AccountOperation)

  private final case class Consumed(lot: Lot, quantity: BigDecimal)

  // What the fold applies at one point of the chronology.
  private enum Step {

    // One operation, or one of a kind that takes effect together with its group but has none.
    case Single(entry: OnInstrument)

    // The operations of one group, dated by the first of them.
    case Group(date: LocalDate, legs: List[OnInstrument])
  }

  // The group's operations are applied once, where the fold reaches the first of them.
  private def stepsOf(ordered: List[OnInstrument]): List[Step] = {
    val groups =
      ordered.filter(entry => AppliedTogether(entry.operation.kind)).groupBy(_.operation.group)
    val (_, steps) = ordered.foldLeft((Set.empty[OperationGroup], List.empty[Step])) {
      case ((applied, reached), entry) =>
        entry.operation.group.filter(_ => AppliedTogether(entry.operation.kind)) match {
          case Some(group) if applied(group) => (applied, reached)
          case Some(group) =>
            val legs = groups(Some(group))
            (applied + group, Step.Group(entry.operation.occurredOn, legs) :: reached)
          case None => (applied, Step.Single(entry) :: reached)
        }
    }
    steps.reverse
  }

  // What the fold has made of the operations so far. `open` has no entry for an instrument without
  // open lots, each instrument's lots are oldest first, and `realized` is newest first.
  private final case class State(
    open: Map[InstrumentKey, List[Lot]],
    realized: List[RealizedGain],
    unmatched: Map[InstrumentKey, BigDecimal]
  ) {

    def lotsOf(instrument: InstrumentKey): List[Lot] = open.getOrElse(instrument, Nil)

    // A lot of no units, such as a buy that executed none, is not a lot. A lot carried over from
    // another one can be older than the lots already open, and goes before the later ones.
    def withLot(lot: Lot): State =
      if (lot.quantity.signum > 0) {
        val (older, newer) = lotsOf(lot.instrument).span(!_.acquiredOn.isAfter(lot.acquiredOn))
        withLots(lot.instrument, older ::: (lot :: newer))
      } else this

    def withLots(instrument: InstrumentKey, lots: List[Lot]): State =
      copy(open = if (lots.isEmpty) open - instrument else open.updated(instrument, lots))

    def withRealized(gain: RealizedGain): State = copy(realized = gain :: realized)

    // Takes `quantity` units from the oldest open lots of `instrument`. Whatever the lots cannot
    // cover is added to the unmatched units of the instrument.
    def consume(instrument: InstrumentKey, quantity: BigDecimal): (List[Consumed], State) = {
      val (consumed, remaining) = TaxLots.consume(lotsOf(instrument), quantity)
      val shortfall = quantity - consumed.iterator.map(_.quantity).sum
      (consumed, withLots(instrument, remaining).withUnmatched(instrument, shortfall))
    }

    private def withUnmatched(instrument: InstrumentKey, quantity: BigDecimal): State =
      if (quantity.signum > 0)
        copy(unmatched =
          unmatched.updated(instrument, unmatched.getOrElse(instrument, Zero) + quantity)
        )
      else this
  }

  private object State {
    val empty: State = State(Map.empty, Nil, Map.empty)
  }

  // The fees and the trades that share a fee: those of one instrument on one day in one currency. A
  // fee is added to a cost, or taken from proceeds, only in its own currency, since the ledger has
  // no exchange rates; a fee in a currency that the instrument has no trade in that day belongs to
  // no trade.
  private final case class FeePool(instrument: InstrumentKey, date: LocalDate, currency: Currency)

  // Each pool's total fee, pro-rated separately into that same day's buys (added to cost) and sells
  // (subtracted from proceeds) in that currency. A single day's fee is used in full for each side
  // independently; this is a documented simplification (docs/tax.md), not a claim that a day never
  // mixes a buy's and a sell's own separate commissions.
  private final class FeePools(
    fees: Map[FeePool, BigDecimal],
    buyQuantities: Map[FeePool, BigDecimal],
    sellQuantities: Map[FeePool, BigDecimal]
  ) {

    def buyShare(entry: OnInstrument): BigDecimal =
      shareOf(entry, entry.operation.quantity, buyQuantities)

    def sellShare(entry: OnInstrument): BigDecimal =
      shareOf(entry, entry.operation.quantity.abs, sellQuantities)

    private def shareOf(
      entry: OnInstrument,
      quantity: BigDecimal,
      quantities: Map[FeePool, BigDecimal]
    ): BigDecimal = {
      val pool = FeePools.poolOf(entry)
      val poolQuantity = quantities.getOrElse(pool, Zero)
      if (poolQuantity <= Zero) Zero else fees.getOrElse(pool, Zero) * quantity / poolQuantity
    }
  }

  private object FeePools {

    def of(entries: List[OnInstrument]): FeePools =
      new FeePools(
        entries
          .filter(_.operation.kind == OperationKind.Fee)
          .groupMapReduce(poolOf)(_.operation.amount.abs)(_ + _),
        quantityByPool(entries, OperationKind.Buy),
        quantityByPool(entries, OperationKind.Sell)
      )

    def poolOf(entry: OnInstrument): FeePool =
      FeePool(entry.instrument, entry.operation.occurredOn, entry.operation.currency)

    private def quantityByPool(
      entries: List[OnInstrument],
      kind: OperationKind
    ): Map[FeePool, BigDecimal] =
      entries
        .filter(_.operation.kind == kind)
        .groupMapReduce(poolOf)(_.operation.quantity.abs)(_ + _)
  }

  // A part of what a conversion gave up: `units` of one lot, or of the units no lot covered.
  private final case class Piece(units: BigDecimal, acquiredOn: LocalDate, cost: BigDecimal)

  // A leg of a conversion that received units, and its share of the cost of what was given up.
  private final case class Received(leg: OnInstrument, costShare: BigDecimal)

  // What the legs of a conversion have in common: the currency of its cash, the fraction of what
  // was given up that the cash disposes of, and what each instrument received takes of the cost.
  private final case class Terms(
    date: LocalDate,
    cashCurrency: Option[Currency],
    disposed: BigDecimal,
    received: List[Received]
  )

  // What one leg that gave up units takes of a conversion: the units of each instrument received,
  // in the order of `Terms.received`, the cash it is paid, and the cash it pays, which adds to the
  // cost of the units received.
  private final case class Portion(units: List[BigDecimal], cash: BigDecimal, paid: BigDecimal)

  private final class Fold(
    fees: FeePools,
    tickerOf: InstrumentKey => Option[Ticker],
    allocations: List[CorporateActionAllocation]
  ) {

    def apply(state: State, step: Step): State =
      step match {
        case Step.Single(entry) => single(state, entry)
        case Step.Group(date, legs) => together(state, date, legs)
      }

    private def single(state: State, entry: OnInstrument): State = {
      val op = entry.operation
      val instrument = entry.instrument
      val date = op.occurredOn
      op.kind match {
        case OperationKind.Buy =>
          val feeShare = fees.buyShare(entry)
          val unitCost =
            if (op.quantity.signum == 0) Zero else (op.amount.abs + feeShare) / op.quantity
          state.withLot(Lot(instrument, op.quantity, unitCost, date))
        case OperationKind.SecurityIn =>
          state.withLot(Lot(instrument, op.quantity, op.price.getOrElse(Zero), date))
        case OperationKind.Sell =>
          val sold = op.quantity.abs
          val proceeds = op.amount.abs - fees.sellShare(entry)
          val (consumed, remaining) = state.consume(instrument, sold)
          val matchedQty = consumed.iterator.map(_.quantity).sum
          val costBasis = consumed.iterator.map(c => c.lot.unitCost * c.quantity).sum
          remaining.withRealized(
            RealizedGain(
              instrument,
              tickerOf(instrument),
              date,
              matchedQty,
              op.currency,
              proceeds,
              costBasis,
              proceeds - costBasis,
              holdingDaysOf(consumed, date, matchedQty)
            )
          )
        case OperationKind.SecurityOut =>
          state.consume(instrument, op.quantity.abs)._2
        case OperationKind.Split =>
          split(state, instrument, op.quantity, date)
        case OperationKind.StockDividend =>
          if (op.quantity.signum < 0) state.consume(instrument, op.quantity.abs)._2
          else state.withLot(Lot(instrument, op.quantity, op.price.getOrElse(Zero), date))
        case OperationKind.Conversion | OperationKind.Spinoff =>
          together(state, date, List(entry))
        case OperationKind.Deposit | OperationKind.Withdrawal | OperationKind.Dividend |
            OperationKind.Coupon | OperationKind.Interest | OperationKind.Fee | OperationKind.Tax |
            OperationKind.Exchange | OperationKind.Other =>
          state
      }
    }

    // The instrument's lots scale their units by (held + quantity) / held and keep their cost, so
    // the unit cost scales the other way; the last lot takes the units the others leave, so the
    // lots add up to the units held exactly. Units that would leave nothing close the lots without
    // a gain, whatever is beyond them being unmatched.
    private def split(
      state: State,
      instrument: InstrumentKey,
      quantity: BigDecimal,
      date: LocalDate
    ): State = {
      val lots = state.lotsOf(instrument)
      val held = lots.iterator.map(_.quantity).sum
      val after = held + quantity
      if (held.signum == 0 && quantity.signum > 0)
        state.withLot(Lot(instrument, quantity, Zero, date))
      else if (after <= Zero) state.consume(instrument, quantity.abs)._2
      else
        state.withLots(
          instrument,
          lots.zip(Proportions.apportion(after, lots.map(_.quantity))).collect {
            case (lot, units) if units.signum > 0 =>
              lot.copy(quantity = units, unitCost = divide(lot.unitCost * lot.quantity, units))
          }
        )
    }

    private def together(state: State, date: LocalDate, legs: List[OnInstrument]): State = {
      val conversions = legs.filter(_.operation.kind == OperationKind.Conversion)
      val spinoffs = legs.filter(_.operation.kind == OperationKind.Spinoff)
      val converted = if (conversions.isEmpty) state else convert(state, date, conversions)
      if (spinoffs.isEmpty) converted else spinOff(converted, date, spinoffs)
    }

    // Each leg that gave up units moves the lots it consumed to what the group received: the units
    // keep their acquisition dates and their cost, apart from the share the cash disposes of,
    // which is a sale. Several legs that gave up units split what was received, and the cash, by
    // their value when every one has a value above zero, else by their units, so each has a share
    // that carries its cost. The shares are apportioned, so they add up to the units received and
    // to the cash exactly.
    private def convert(state: State, date: LocalDate, legs: List[OnInstrument]): State = {
      val gaveUp = legs.filter(_.operation.quantity.signum < 0)
      val received = legs.filter(_.operation.quantity.signum > 0)
      val cash = legs.iterator.map(_.operation.amount).sum
      val valueReceived = received.iterator.flatMap(valueOf).sum
      val terms = Terms(
        date,
        legs.find(_.operation.amount.signum != 0).map(_.operation.currency),
        if (cash.signum > 0) divide(cash, cash + valueReceived) else Zero,
        received.zip(sharesOf(received)).map { case (leg, share) => Received(leg, share) }
      )
      val proceeds = cash.max(Zero)
      val paid = (-cash).max(Zero)
      if (gaveUp.isEmpty)
        convertedFrom(
          state,
          None,
          Portion(received.map(_.operation.quantity), proceeds, paid),
          terms
        )
      else {
        val weights = outLegWeights(gaveUp)
        val unitsReceived =
          received.map(leg => Proportions.apportion(leg.operation.quantity, weights))
        val portions = gaveUp.indices.toList
          .map(index => unitsReceived.map(_(index)))
          .lazyZip(Proportions.apportion(proceeds, weights))
          .lazyZip(Proportions.apportion(paid, weights))
          .map(Portion(_, _, _))
        gaveUp.zip(portions).foldLeft(state) { case (current, (leg, portion)) =>
          convertedFrom(current, Some(leg), portion, terms)
        }
      }
    }

    // The part of the conversion that `from` gave up. Units received for nothing that was given up,
    // as when no leg gave anything up, form a lot on the date of the conversion. The units of each
    // instrument received, and the cash paid, are apportioned among the pieces given up by their
    // units, so the lots add up to the units received exactly.
    private def convertedFrom(
      state: State,
      from: Option[OnInstrument],
      portion: Portion,
      terms: Terms
    ): State = {
      val units = from.fold(BigDecimal(1))(_.operation.quantity.abs)
      val (consumed, remaining) =
        from.fold((List.empty[Consumed], state))(leg => state.consume(leg.instrument, units))
      val matched = consumed.iterator.map(_.quantity).sum
      val pieces =
        consumed.map(c => Piece(c.quantity, c.lot.acquiredOn, c.lot.unitCost * c.quantity)) ++
          Option.when(units > matched)(Piece(units - matched, terms.date, Zero))
      val pieceUnits = pieces.map(_.units)
      val paidByPiece = Proportions.apportion(portion.paid, pieceUnits)
      val lots = for {
        (received, unitsReceived) <- terms.received.zip(portion.units)
        ((piece, quantity), paid) <-
          pieces.zip(Proportions.apportion(unitsReceived, pieceUnits)).zip(paidByPiece)
        if quantity.signum > 0
      } yield {
        val cost = received.costShare * ((BigDecimal(1) - terms.disposed) * piece.cost + paid)
        Lot(received.leg.instrument, quantity, divide(cost, quantity), piece.acquiredOn)
      }
      val sale = from
        .filter(_ => terms.disposed.signum > 0)
        .map(realizedFrom(_, portion.cash, consumed, matched, terms))
      lots.foldLeft(sale.fold(remaining)(remaining.withRealized))(_.withLot(_))
    }

    // The sale of the share of the consumed lots that the cash pays for.
    private def realizedFrom(
      leg: OnInstrument,
      proceeds: BigDecimal,
      consumed: List[Consumed],
      matched: BigDecimal,
      terms: Terms
    ): RealizedGain = {
      val costBasis = terms.disposed * consumed.iterator.map(c => c.lot.unitCost * c.quantity).sum
      RealizedGain(
        leg.instrument,
        tickerOf(leg.instrument),
        terms.date,
        terms.disposed * matched,
        terms.cashCurrency.getOrElse(leg.operation.currency),
        proceeds,
        costBasis,
        proceeds - costBasis,
        holdingDaysOf(consumed, terms.date, matched)
      )
    }

    // The new instruments take lots out of each lot of the parent, the parent keeping the rest of
    // the cost; a new instrument's units are apportioned among the parent's lots, so its lots add
    // up to them exactly. Without a parent, or one with no open lots, a new instrument's lot costs
    // nothing and is dated by the spin-off. A leg that gives up units, which no broker gives, is a
    // transfer out.
    private def spinOff(state: State, date: LocalDate, legs: List[OnInstrument]): State = {
      val withdrawn = legs.filter(_.operation.quantity.signum < 0)
      val children = legs.filter(_.operation.quantity.signum > 0)
      val parent = legs.find(_.operation.quantity.signum == 0).map(_.instrument)
      val afterWithdrawals =
        withdrawn.foldLeft(state)((s, leg) =>
          s.consume(leg.instrument, leg.operation.quantity.abs)._2
        )
      parent.filter(afterWithdrawals.lotsOf(_).nonEmpty) match {
        case Some(instrument) =>
          val parentLots = afterWithdrawals.lotsOf(instrument)
          val fractions = allocatedFractions(children)
          val parentUnits = parentLots.map(_.quantity)
          val lots = children.zip(fractions).flatMap { case (child, fraction) =>
            parentLots
              .zip(Proportions.apportion(child.operation.quantity, parentUnits))
              .collect {
                case (lot, quantity) if quantity.signum > 0 =>
                  val cost = fraction * lot.quantity * lot.unitCost
                  Lot(child.instrument, quantity, divide(cost, quantity), lot.acquiredOn)
              }
          }
          val kept = BigDecimal(1) - fractions.sum
          val reduced = parentLots.map(lot => lot.copy(unitCost = lot.unitCost * kept))
          lots.foldLeft(afterWithdrawals.withLots(instrument, reduced))(_.withLot(_))
        case None =>
          children.foldLeft(afterWithdrawals) { (current, child) =>
            current.withLot(Lot(child.instrument, child.operation.quantity, Zero, date))
          }
      }
    }

    // The fraction of the parent's cost each new instrument takes: the allocation, kept within 0
    // and 1, and scaled down when the allocations of the spin-off add up to more than the whole.
    private def allocatedFractions(children: List[OnInstrument]): List[BigDecimal] = {
      val requested = children.map(allocationOf)
      val total = requested.sum
      if (total > BigDecimal(1)) requested.map(divide(_, total)) else requested
    }

    private def allocationOf(child: OnInstrument): BigDecimal =
      child.operation.group
        .flatMap(group =>
          allocations.find(a => a.group == group && a.instrument == child.instrument)
        )
        .fold(Zero)(_.costFraction.max(Zero).min(BigDecimal(1)))
  }

  private def valueOf(leg: OnInstrument): Option[BigDecimal] =
    leg.operation.price.map(_ * leg.operation.quantity.abs)

  // The fraction each leg has of `legs`: by market value when every leg has a price, else by units.
  private def sharesOf(legs: List[OnInstrument]): List[BigDecimal] = {
    val values = legs.map(valueOf)
    val byValue = values.forall(_.isDefined) && values.flatten.sum.signum > 0
    val weights = if (byValue) values.flatten else legs.map(_.operation.quantity.abs)
    val total = weights.sum
    weights.map(divide(_, total))
  }

  // How the legs that gave up units share what a conversion pays: by market value when every one
  // of them has a value above zero, else by units. A leg with no share would have no lot to carry
  // its cost.
  private def outLegWeights(legs: List[OnInstrument]): List[BigDecimal] = {
    val values = legs.map(valueOf)
    if (values.forall(_.exists(_.signum > 0))) values.flatten
    else legs.map(_.operation.quantity.abs)
  }

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
