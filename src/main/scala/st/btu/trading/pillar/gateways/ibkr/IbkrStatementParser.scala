package st.btu.trading.pillar.gateways.ibkr

import cats.syntax.either.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.models.domain.{BrokerInstrumentId, Currency}
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.ibkr.IbkrAttributes.Name
import st.btu.trading.pillar.models.market.{ClassCode, Ticker}

import java.time.LocalDate
import javax.xml.stream.{XMLStreamException, XMLStreamReader}
import scala.annotation.tailrec

/** Reads the XML an Activity Flex Query returns, and keeps what the sync needs from it.
  *
  * A response holds one statement per account. Of a statement it keeps:
  *   - the trades at the execution level; IB reports the same fills again as orders and as symbol
  *     and asset summaries, which are other elements and are left out;
  *   - the cash transactions and the corporate actions at the detail level; a summary row repeats a
  *     detail row and has no transaction id;
  *   - the transfers;
  *   - the instruments of the Financial Instrument Information section;
  *   - the open positions at the summary level, or naming no level, which are not rows to store but
  *     what the statement reports as held at its end.
  *
  * Every attribute of a kept row is retained as sent. Elements and attributes the parser does not
  * know are ignored. The XML is read without document type declarations and external entities.
  */
object IbkrStatementParser {

  private val RootElement = "FlexQueryResponse"
  private val StatementElement = "FlexStatement"
  private val SecurityElement = "SecurityInfo"
  private val OpenPositionElement = "OpenPosition"

  private val AccountAttribute = "accountId"
  private val FromDateAttribute = "fromDate"
  private val ToDateAttribute = "toDate"

  private val ExecutionLevel = "EXECUTION"
  private val DetailLevel = "DETAIL"
  private val SummaryLevel = "SUMMARY"

  private final case class XmlElement(name: String, attributes: Map[String, String])

  // What to do with a row that has no transaction id: it is a summary of other rows and is left
  // out, or the query lacks a field the sync needs and the statement is refused.
  private enum WithoutId {
    case Skip, Fail
  }

  // Which rows of an element are kept: only those at `level` when the row names one, and those
  // with a transaction id.
  private final case class RowRule(
    section: IbkrSection,
    level: Option[String],
    withoutId: WithoutId
  )

  private val RowRules: Map[String, RowRule] = Map(
    "Trade" -> RowRule(IbkrSection.Trade, Some(ExecutionLevel), WithoutId.Fail),
    "CashTransaction" -> RowRule(IbkrSection.CashTransaction, Some(DetailLevel), WithoutId.Skip),
    "Transfer" -> RowRule(IbkrSection.Transfer, None, WithoutId.Fail),
    "CorporateAction" -> RowRule(IbkrSection.CorporateAction, Some(DetailLevel), WithoutId.Skip)
  )

  private val KeptElements: Set[String] =
    RowRules.keySet + StatementElement + SecurityElement + OpenPositionElement

  /** The statements in the response `xml`, in the order the response lists them. Fails when the
    * text is not an Activity Flex Query response, or when a kept row lacks an attribute the sync
    * needs.
    */
  def parse(xml: String): Either[IbkrStatementError, List[IbkrStatement]] =
    for {
      elements <- keptElements(xml)
      statements <- groupedByStatement(elements).traverse { case (header, body) =>
        statementOf(header, body)
      }
    } yield statements

  private def keptElements(xml: String): Either[IbkrStatementError, List[XmlElement]] =
    Either
      .catchOnly[XMLStreamException] {
        val reader = SecureXml.reader(xml)
        try startedElements(reader)
        finally reader.close()
      }
      .leftMap(e => IbkrStatementError.Malformed(Option(e.getMessage).getOrElse("not XML")))
      .flatMap {
        case Nil => Left(IbkrStatementError.Malformed("the document is empty"))
        case root :: _ if root.name != RootElement =>
          Left(IbkrStatementError.Malformed(s"expected $RootElement but found ${root.name}"))
        case _ :: elements => Right(elements.filter(element => KeptElements(element.name)))
      }

  // Every element in document order, with the attributes of those worth keeping.
  private def startedElements(reader: XMLStreamReader): List[XmlElement] =
    SecureXml
      .readStartElements(reader) { element =>
        val name = element.getLocalName
        XmlElement(name, if (KeptElements(name)) attributesOf(element) else Map.empty)
      }
      .toList

  private def attributesOf(reader: XMLStreamReader): Map[String, String] =
    (0 until reader.getAttributeCount)
      .map(index => reader.getAttributeLocalName(index) -> reader.getAttributeValue(index))
      .toMap

  // Each FlexStatement with the elements that follow it, up to the next FlexStatement.
  @tailrec
  private def groupedByStatement(
    elements: List[XmlElement],
    grouped: List[(XmlElement, List[XmlElement])] = Nil
  ): List[(XmlElement, List[XmlElement])] =
    elements match {
      case Nil => grouped.reverse
      case header :: rest if header.name == StatementElement =>
        val (body, next) = rest.span(_.name != StatementElement)
        groupedByStatement(next, (header, body) :: grouped)
      case _ :: rest => groupedByStatement(rest, grouped)
    }

  private def statementOf(
    header: XmlElement,
    body: List[XmlElement]
  ): Either[IbkrStatementError, IbkrStatement] = {
    def malformed(detail: String): IbkrStatementError =
      IbkrStatementError.Malformed(s"a $StatementElement $detail")

    def attribute(name: String): Either[IbkrStatementError, String] =
      IbkrAttributes.value(header.attributes, name).toRight(malformed(s"has no $name"))

    def date(name: String): Either[IbkrStatementError, LocalDate] =
      attribute(name).flatMap(text =>
        IbkrAttributes.date(text).toRight(malformed(s"has an invalid $name: '$text'"))
      )

    val ruled = body.flatMap(element => RowRules.get(element.name).map(element -> _))
    for {
      account <- attribute(AccountAttribute)
      from <- date(FromDateAttribute)
      to <- date(ToDateAttribute)
      rows <- ruled.traverse { case (element, rule) => rowOf(rule, element) }
    } yield IbkrStatement(
      IbkrAccountId(account),
      from,
      to,
      rows.flatten,
      body.filter(_.name == SecurityElement).flatMap(securityOf),
      body.filter(_.name == OpenPositionElement).flatMap(openPositionOf)
    )
  }

  private def rowOf(
    rule: RowRule,
    element: XmlElement
  ): Either[IbkrStatementError, Option[IbkrReportedRow]] = {
    def attribute(name: String): Option[String] = IbkrAttributes.value(element.attributes, name)

    val atAnotherLevel =
      rule.level.exists(expected => attribute(Name.LevelOfDetail).exists(_ != expected))
    if (atAnotherLevel) Right(None)
    else
      attribute(Name.TransactionId) match {
        case Some(id) =>
          val transactionId = IbkrTransactionId(id)
          for {
            text <- attribute(Name.ReportDate).toRight[IbkrStatementError](
              IbkrStatementError
                .MissingAttribute(rule.section, Some(transactionId), Name.ReportDate)
            )
            date <- IbkrAttributes
              .date(text)
              .toRight[IbkrStatementError](
                IbkrStatementError
                  .InvalidAttribute(rule.section, Some(transactionId), Name.ReportDate, text)
              )
          } yield Some(IbkrReportedRow(rule.section, transactionId, date, element.attributes))
        case None =>
          rule.withoutId match {
            case WithoutId.Skip => Right(None)
            case WithoutId.Fail =>
              Left(IbkrStatementError.MissingAttribute(rule.section, None, Name.TransactionId))
          }
      }
  }

  // An instrument without a contract id or a symbol cannot be recorded, and is left out.
  private def securityOf(element: XmlElement): Option[IbkrSecurityInfo] = {
    def attribute(name: String): Option[String] = IbkrAttributes.value(element.attributes, name)

    for {
      conid <- attribute(Name.Conid)
      symbol <- attribute(Name.Symbol)
    } yield IbkrSecurityInfo(
      BrokerInstrumentId(conid),
      Ticker(symbol),
      attribute(Name.ListingExchange).map(ClassCode(_)),
      attribute(Name.Isin),
      attribute(Name.Description),
      attribute(Name.AssetCategory),
      attribute(Name.Currency).map(Currency(_))
    )
  }

  // A position without a contract id or a quantity cannot be compared with the recorded one, and
  // is left out. So is a row of a lot, which breaks a position into its purchases.
  private def openPositionOf(element: XmlElement): Option[IbkrOpenPosition] = {
    def attribute(name: String): Option[String] = IbkrAttributes.value(element.attributes, name)

    val atAnotherLevel = attribute(Name.LevelOfDetail).exists(_ != SummaryLevel)
    if (atAnotherLevel) None
    else
      for {
        conid <- attribute(Name.Conid)
        quantity <- attribute(Name.Position).flatMap(IbkrAttributes.decimal)
      } yield IbkrOpenPosition(
        BrokerInstrumentId(conid),
        attribute(Name.Symbol).map(Ticker(_)),
        attribute(Name.AssetCategory),
        quantity
      )
  }
}
