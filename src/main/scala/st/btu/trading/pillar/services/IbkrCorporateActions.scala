package st.btu.trading.pillar.services

import cats.syntax.traverse.*
import st.btu.trading.pillar.analytics.Proportions
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.ibkr.IbkrAttributes.Name
import st.btu.trading.pillar.strategy.indicators.Indicators.divide

import java.time.{Instant, LocalDate, ZoneId}

/** Interprets the legs of an account's corporate actions as `AccountOperation`s. Pure: it is given
  * all the legs of the account at once, since what a leg means depends on the other legs of its
  * action, and a cancelling leg names a leg reported earlier.
  *
  * IB reports an action as one row per instrument it changes, which share an `actionID`; each row's
  * `quantity` is the signed change of its own instrument and its `proceeds` the cash it carries. An
  * action is read by its type code first, and by the shape of its legs when the code is not one the
  * library reads for those legs; `docs/ibkr.md` tables the codes, the shapes and what each leg
  * becomes.
  */
private[services] object IbkrCorporateActions {

  /** The records of the legs, in the order the legs were given, and the notices about how they were
    * read.
    */
  final case class Interpretation(records: List[IbkrRowRecord], notices: List[Notice])

  /** The action a notice is about, as IB names it. */
  final case class ActionSummary(
    id: String,
    code: Option[String],
    date: LocalDate,
    description: Option[String]
  )

  /** Something the bot did with an action that its owner is told to check. */
  sealed trait Notice {
    def action: ActionSummary
  }

  object Notice {

    /** The action is not one the library interprets, so its legs stay as shares in and out. */
    final case class NotInterpreted(action: ActionSummary) extends Notice

    /** The action's code is not one the library reads for these legs, so they were read by their
      * shape, and became operations of `kind`.
      */
    final case class ReadByShape(action: ActionSummary, kind: OperationKind) extends Notice

    /** IB reports no value for the shares of a stock dividend, so they open a lot at no cost and
      * count no income.
      */
    final case class StockDividendWithoutValue(action: ActionSummary) extends Notice

    /** No parent was found for a spin-off, so the new shares start at no cost on the day of the
      * action.
      */
    final case class SpinOffWithoutParent(action: ActionSummary) extends Notice
  }

  /** Interprets `rows`, the corporate action legs of one account.
    *
    * @param others
    *   rows to look for the parents of spin-offs among, besides `rows` themselves. The parent is
    *   the instrument of the row, other than a leg of the action, whose `isin` is the one in
    *   parentheses in the description of the leg; of several, the latest by report date and then
    *   transaction id
    * @return
    *   the interpretation, or the reason a leg cannot be read: an attribute it needs is missing or
    *   malformed
    */
  def interpret(
    rows: List[IbkrStatementRow],
    others: Iterable[IbkrStatementRow],
    zone: ZoneId
  ): Either[IbkrStatementError, Interpretation] =
    rows.zipWithIndex.traverse { case (row, position) => Leg.of(row, position, zone) }.map { legs =>
      val cancelled = cancelledRefs(legs)
      val (voided, standing) = legs.partition(leg => cancelled(leg.ref))
      val parents = new Parents(others ++ rows)
      val results = actionsOf(standing).map(readAction(_, parents))
      val translated = voided.map(standalone(_, OperationState.Canceled)) ++
        results.flatMap(_.translated)
      Interpretation(
        translated.sortBy(_.leg.position).map(t => IbkrRowRecord(t.leg.row, t.operations)),
        results.flatMap(_.notices).distinct
      )
    }

  /** The ISINs the descriptions of `rows` name, which is where to look for the parents of their
    * spin-offs.
    */
  def isinsNamed(rows: Iterable[IbkrStatementRow]): Set[Isin] =
    rows.flatMap(_.attribute(Name.Description).flatMap(isinOf)).toSet

  /** The text of `notice` for the alert that tells the owner about it. */
  def describe(notice: Notice): String = {
    val action = notice.action
    val what = notice match {
      case Notice.NotInterpreted(_) =>
        "not interpreted, so its legs are recorded as shares in and out, which keeps the positions " +
          "but carries no cost over"
      case Notice.ReadByShape(_, kind) =>
        s"its type is not read for these legs, so they were read by their shape as ${kind.value}"
      case Notice.StockDividendWithoutValue(_) =>
        "IB gives no value for the shares received, so they open a lot at no cost and count no income"
      case Notice.SpinOffWithoutParent(_) =>
        s"no parent was found, so the new shares start at no cost on ${action.date}"
    }
    val code = action.code.fold("")(code => s" ($code)")
    val description = action.description.fold("")(description => s", \"$description\"")
    s"Action ${action.id}$code of ${action.date}$description: $what"
  }

  private val ParentSuffix = "parent"

  // IB's type codes of a corporate action, of those the library reads.
  private val ForwardSplitCode = "FS"
  private val ReverseSplitCode = "RS"
  private val IssueChangeCode = "IC"
  private val MergerCode = "TC"
  private val VoluntaryConversionCode = "TO"
  private val SubscribeRightsCode = "SR"
  private val DelistWorthlessCode = "DW"
  private val TBillMaturityCode = "TM"
  private val StockDividendCode = "SD"
  private val SpinOffCode = "SO"
  private val RightsIssueCode = "RI"

  // The rest of the codes IB documents, which are read by the shape of the legs.
  private val OtherDocumentedCodes: Set[String] =
    "OR BC BM CD HD HI CC CA CO CS CI CP DI ED UE FA FI GV PC PI TI UB CH CT PV".split(' ').toSet

  // A cancelling leg is noted `Ca` among the codes of the row, which are separated by semicolons.
  private val CancelCode = "Ca"
  private val CodeSeparator = ";"

  // An ISIN in parentheses, as the description of a leg names the instrument its action is about.
  private val IsinInParentheses = "\\(([A-Z]{2}[A-Z0-9]{9}[0-9])\\)".r

  private def isinOf(description: String): Option[Isin] =
    IsinInParentheses.findFirstMatchIn(description).map(found => Isin(found.group(1)))

  private enum Reading(val kind: OperationKind) {
    case Split extends Reading(OperationKind.Split)
    case Conversion extends Reading(OperationKind.Conversion)
    case Disposal extends Reading(OperationKind.Sell)
    case StockDividend extends Reading(OperationKind.StockDividend)
    case SpinOff extends Reading(OperationKind.Spinoff)
  }

  private enum Basis {
    case Code, Shape
  }

  private final case class Reads(reading: Reading, basis: Basis)

  // What the legs of an action look like: the legs that gave up units, those that received them, and
  // whether any carries cash.
  private final case class Shape(outs: List[Leg], ins: List[Leg], hasCash: Boolean) {
    def isLone: Boolean = outs.size + ins.size == 1
    def isBoth: Boolean = outs.nonEmpty && ins.nonEmpty
    def isOutsOnly: Boolean = outs.nonEmpty && ins.isEmpty
    def isInsOnly: Boolean = ins.nonEmpty && outs.isEmpty
  }

  // A row of the table of codes: the codes it reads, and the legs it reads them for.
  private final case class CodeRule(codes: Set[String], fits: Shape => Boolean, reading: Reading)

  private val CodeRules = List(
    CodeRule(Set(ForwardSplitCode, ReverseSplitCode), s => s.isLone && !s.hasCash, Reading.Split),
    CodeRule(
      Set(
        ForwardSplitCode,
        ReverseSplitCode,
        IssueChangeCode,
        MergerCode,
        VoluntaryConversionCode,
        SubscribeRightsCode
      ),
      _.isBoth,
      Reading.Conversion
    ),
    CodeRule(
      Set(MergerCode, DelistWorthlessCode, TBillMaturityCode),
      _.isOutsOnly,
      Reading.Disposal
    ),
    CodeRule(Set(StockDividendCode), s => s.isInsOnly && !s.hasCash, Reading.StockDividend),
    CodeRule(Set(SpinOffCode, RightsIssueCode), s => s.isInsOnly && !s.hasCash, Reading.SpinOff)
  )

  private val DocumentedCodes: Set[String] =
    CodeRules.flatMap(_.codes).toSet ++ OtherDocumentedCodes

  // A shape that reads the legs of an action whatever its code, among the documented ones.
  private final case class ShapeRule(fits: Shape => Boolean, reading: Reading)

  private val ShapeRules = List(
    ShapeRule(
      shape => shape.isLone && !shape.hasCash && (shape.outs ++ shape.ins).forall(_.isOnDescribed),
      Reading.Split
    ),
    ShapeRule(_.isBoth, Reading.Conversion),
    ShapeRule(_.isOutsOnly, Reading.Disposal),
    ShapeRule(
      shape => shape.isInsOnly && !shape.hasCash && !shape.ins.exists(_.isOnDescribed),
      Reading.SpinOff
    )
  )

  private def readingOf(code: Option[String], shape: Shape): Option[Reads] =
    code.filter(DocumentedCodes) match {
      case None => None
      case Some(documented) =>
        CodeRules
          .find(rule => rule.codes(documented) && rule.fits(shape))
          .map(rule => Reads(rule.reading, Basis.Code))
          .orElse(ShapeRules.find(_.fits(shape)).map(rule => Reads(rule.reading, Basis.Shape)))
    }

  private final case class Leg(
    row: IbkrStatementRow,
    position: Int,
    actionId: Option[String],
    actionCode: Option[String],
    cancelling: Boolean,
    instrument: Option[InstrumentKey],
    isin: Option[Isin],
    description: Option[String],
    quantity: BigDecimal,
    cash: BigDecimal,
    value: Option[BigDecimal],
    currency: Currency,
    occurredAt: Instant,
    date: LocalDate
  ) {
    def ref: OperationRef = row.ref

    def group: Option[OperationGroup] = actionId.map(OperationGroup(_))

    // The market value per unit, where the leg has a value and units.
    def price: Option[BigDecimal] =
      value.filter(v => v.signum != 0 && quantity.signum != 0).map(v => divide(v.abs, quantity.abs))

    // Whether the leg is on the instrument its description names.
    def isOnDescribed: Boolean =
      description.flatMap(isinOf).exists(described => isin.contains(described))
  }

  private object Leg {

    def of(row: IbkrStatementRow, position: Int, zone: ZoneId): Either[IbkrStatementError, Leg] = {
      val fields = new IbkrRowFields(row)
      for {
        currency <- fields.currency(Name.Currency)
        quantity <- fields.decimalOrZero(Name.Quantity)
        cash <- fields.decimalOrZero(Name.Proceeds)
        value <- fields.decimal(Name.Value)
        instrument <-
          if (quantity.signum != 0) fields.requiredInstrument.map(Some(_))
          else Right(fields.instrument)
        occurredAt <- fields.occurredAt(zone)
      } yield Leg(
        row,
        position,
        fields.text(Name.ActionId),
        fields.text(Name.Type),
        fields.text(Name.Code).exists(_.split(CodeSeparator).exists(_.trim == CancelCode)),
        instrument,
        fields.text(Name.Isin).map(Isin(_)),
        fields.text(Name.Description),
        quantity,
        cash,
        value,
        currency,
        occurredAt,
        LocalDate.ofInstant(occurredAt, zone)
      )
    }
  }

  // IB hands out transaction ids as numbers that grow, so a longer one is a later one.
  private def recency(row: IbkrStatementRow): (Long, (Int, String)) =
    (row.reportDate.toEpochDay, (row.transactionId.value.length, row.transactionId.value))

  private def order(leg: Leg): (Long, (Int, String)) = recency(leg.row)

  // The legs a cancelling leg cancels, and the cancelling legs themselves. A cancelling leg cancels
  // the leg of the same instrument and type with the opposite quantity that was reported last on or
  // before it, other than a cancelling leg and a leg that another one cancels; one that finds none
  // is cancelled alone.
  private def cancelledRefs(legs: List[Leg]): Set[OperationRef] = {
    val cancelling = legs.filter(_.cancelling).sortBy(order)
    val candidates = legs.filterNot(_.cancelling)
    val cancelled = cancelling.foldLeft(Set.empty[OperationRef]) { (taken, cancel) =>
      candidates
        .filter { leg =>
          cancel.instrument.isDefined && leg.instrument == cancel.instrument &&
          leg.actionCode == cancel.actionCode && leg.quantity == -cancel.quantity &&
          !leg.row.reportDate.isAfter(cancel.row.reportDate) && !taken(leg.ref)
        }
        .maxByOption(order)
        .fold(taken)(target => taken + target.ref)
    }
    cancelled ++ cancelling.map(_.ref)
  }

  // The legs of one action: those sharing an `actionID`, or a leg that names none, which is an
  // action of its own. `first` is the leg reported first.
  private final case class Action(first: Leg, others: List[Leg]) {
    def legs: List[Leg] = first :: others

    def id: String = first.actionId.getOrElse(first.row.transactionId.value)

    // The type the legs share; none when they name none or disagree.
    def code: Option[String] =
      others.foldLeft(first.actionCode)((shared, leg) =>
        if (shared == leg.actionCode) shared else None
      )

    def owns(row: IbkrStatementRow): Boolean = legs.exists(_.ref == row.ref)

    def summary: ActionSummary =
      ActionSummary(id, code, first.date, legs.flatMap(_.description).headOption)
  }

  private def actionsOf(legs: List[Leg]): List[Action] = {
    val (identified, anonymous) = legs.partition(_.actionId.isDefined)
    val groups = identified.groupBy(_.actionId).values.toList ++ anonymous.map(List(_))
    groups
      .map(_.sortBy(order))
      .collect { case first :: others => Action(first, others) }
      .sortBy(action => order(action.first))
  }

  // Finds the parent of a spin-off: the instrument of the latest row with the ISIN that the
  // description of the leg names, among the rows given and not the legs of the action.
  private final class Parents(rows: Iterable[IbkrStatementRow]) {

    private val byIsin: Map[Isin, List[IbkrStatementRow]] =
      rows.iterator
        .filter(_.attribute(Name.Conid).isDefined)
        .flatMap(row => row.attribute(Name.Isin).map(Isin(_) -> row))
        .toList
        .groupMap { case (isin, _) => isin } { case (_, row) => row }

    def of(leg: Leg, action: Action): Option[InstrumentKey] =
      leg.description
        .flatMap(isinOf)
        .flatMap(byIsin.get)
        .flatMap(_.filterNot(action.owns).maxByOption(recency))
        .flatMap(_.attribute(Name.Conid))
        .map(IbkrRowFields.instrumentOf)
  }

  private final case class Translated(leg: Leg, operations: List[AccountOperation])

  private final case class ActionResult(translated: List[Translated], notices: List[Notice])

  private def readAction(action: Action, parents: Parents): ActionResult = {
    val legs = action.legs
    val shape = Shape(
      legs.filter(_.quantity.signum < 0),
      legs.filter(_.quantity.signum > 0),
      legs.exists(_.cash.signum != 0)
    )
    readingOf(action.code, shape).filter(reads => inOneCurrency(reads.reading, shape, legs)) match {
      case None =>
        ActionResult(
          legs.map(standalone(_, OperationState.Executed)),
          List(Notice.NotInterpreted(action.summary))
        )
      case Some(Reads(reading, basis)) =>
        val parentOf: Map[OperationRef, InstrumentKey] =
          if (reading == Reading.SpinOff)
            shape.ins.flatMap(leg => parents.of(leg, action).map(leg.ref -> _)).toMap
          else Map.empty
        val proceeds: Map[OperationRef, BigDecimal] =
          if (reading == Reading.Disposal) saleProceeds(shape, legs) else Map.empty
        val summary = action.summary
        val notices = List(
          Option.when(basis == Basis.Shape)(Notice.ReadByShape(summary, reading.kind)),
          Option.when(reading == Reading.StockDividend && shape.ins.exists(_.price.isEmpty))(
            Notice.StockDividendWithoutValue(summary)
          ),
          Option.when(
            reading == Reading.SpinOff && shape.ins.exists(leg => !parentOf.contains(leg.ref))
          )(Notice.SpinOffWithoutParent(summary))
        ).flatten
        ActionResult(
          legs.map(leg =>
            Translated(leg, operationsOf(leg, reading, parentOf.get(leg.ref), proceeds))
          ),
          notices
        )
    }
  }

  // Whether the cash of an action is in one currency as its operations carry it: a sale carries the
  // cash of its legs of no units on the legs that gave up units, so their currency counts too.
  private def inOneCurrency(reading: Reading, shape: Shape, legs: List[Leg]): Boolean = {
    val carriers = legs.filter(_.cash.signum != 0)
    val paid =
      if (reading == Reading.Disposal && carriers.exists(_.quantity.signum == 0)) shape.outs
      else Nil
    (carriers ++ paid).map(_.currency).distinct.size <= 1
  }

  // What each leg that gave up units is paid in a sale: its own cash, and its share, by its units,
  // of the cash of the action's legs of no units.
  private def saleProceeds(shape: Shape, legs: List[Leg]): Map[OperationRef, BigDecimal] = {
    val unassigned = legs.filter(_.quantity.signum == 0).map(_.cash).sum
    val shares = Proportions.apportion(unassigned, shape.outs.map(_.quantity.abs))
    shape.outs.zip(shares).map { case (leg, share) => leg.ref -> (leg.cash + share) }.toMap
  }

  private def operationsOf(
    leg: Leg,
    reading: Reading,
    parent: Option[InstrumentKey],
    proceeds: Map[OperationRef, BigDecimal]
  ): List[AccountOperation] =
    // Outside a conversion, whose cash is the cash of all its legs, a leg of no units keeps none:
    // a sale pays it to the legs that gave up units, and no other reading has any on such a leg.
    if (leg.quantity.signum == 0 && reading != Reading.Conversion)
      List(ofLeg(leg, OperationKind.Other, None, BigDecimal(0)))
    else
      reading match {
        case Reading.Split => List(ofLeg(leg, reading.kind, None, BigDecimal(0)))
        case Reading.Conversion => List(ofLeg(leg, reading.kind, leg.price, leg.cash))
        case Reading.Disposal =>
          List(ofLeg(leg, reading.kind, None, proceeds.getOrElse(leg.ref, leg.cash)))
        case Reading.StockDividend => List(ofLeg(leg, reading.kind, leg.price, BigDecimal(0)))
        case Reading.SpinOff =>
          ofLeg(leg, reading.kind, None, BigDecimal(0)) :: parent.toList.map(marker(leg, _))
      }

  private def standalone(leg: Leg, state: OperationState): Translated =
    Translated(leg, List(standaloneOperation(leg, state)))

  // The leg as it stands, which is how a leg that is not interpreted is recorded: shares in or out
  // by the sign of its quantity, carrying its cash.
  private def standaloneOperation(leg: Leg, state: OperationState): AccountOperation = {
    val kind =
      if (leg.quantity.signum > 0) OperationKind.SecurityIn
      else if (leg.quantity.signum < 0) OperationKind.SecurityOut
      else OperationKind.Other
    operation(leg, leg.ref, leg.instrument, kind, leg.quantity, None, leg.cash, state)
  }

  private def ofLeg(
    leg: Leg,
    kind: OperationKind,
    price: Option[BigDecimal],
    amount: BigDecimal
  ): AccountOperation =
    operation(
      leg,
      leg.ref,
      leg.instrument,
      kind,
      leg.quantity,
      price,
      amount,
      OperationState.Executed
    )

  // The leg of no units that names the parent of the spin-off a leg is the new instrument of.
  private def marker(leg: Leg, parent: InstrumentKey): AccountOperation =
    operation(
      leg,
      leg.row.refWith(ParentSuffix),
      Some(parent),
      OperationKind.Spinoff,
      BigDecimal(0),
      None,
      BigDecimal(0),
      OperationState.Executed
    )

  private def operation(
    leg: Leg,
    ref: OperationRef,
    instrument: Option[InstrumentKey],
    kind: OperationKind,
    quantity: BigDecimal,
    price: Option[BigDecimal],
    amount: BigDecimal,
    state: OperationState
  ): AccountOperation =
    AccountOperation(
      leg.row.accountId,
      ref,
      kind,
      state,
      leg.occurredAt,
      instrument,
      quantity,
      price,
      amount,
      leg.currency,
      BrokerOperationType(leg.actionCode.getOrElse(kind.value)),
      leg.group
    )
}
