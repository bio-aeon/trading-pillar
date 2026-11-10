package st.btu.trading.pillar.gateways.ibkr

import cats.syntax.either.*

import javax.xml.stream.{XMLStreamException, XMLStreamReader}

// What the Flex Web Service answers to a call: a reference code once it accepts a request, the
// statement once it is generated, or an error code with a message. The reference code names a
// statement that only the token's owner may fetch and the statement is the owner's data, so
// neither is printed.
private[ibkr] sealed trait FlexResponse

private[ibkr] object FlexResponse {

  final case class Accepted(referenceCode: String) extends FlexResponse {
    override def toString: String = "Accepted(***)"
  }

  final case class Statement(xml: String) extends FlexResponse {
    override def toString: String = s"Statement(${xml.length} characters)"
  }

  final case class Refused(code: Int, message: String) extends FlexResponse

  private val StatementRoot = "FlexQueryResponse"
  private val ResponseRoot = "FlexStatementResponse"

  private val StatusField = "Status"
  private val ReferenceCodeField = "ReferenceCode"
  private val ErrorCodeField = "ErrorCode"
  private val ErrorMessageField = "ErrorMessage"
  private val SuccessStatus = "Success"

  /** The answer in `body`. An error code makes it a refusal whatever the status says. */
  def parse(body: String): Either[FlexError.UnexpectedResponse, FlexResponse] =
    Either
      .catchOnly[XMLStreamException] {
        val reader = SecureXml.reader(body)
        try read(reader, body)
        finally reader.close()
      }
      .leftMap(_ => unexpected("the body is not XML"))
      .flatten

  private def read(
    reader: XMLStreamReader,
    body: String
  ): Either[FlexError.UnexpectedResponse, FlexResponse] = {
    val elements = SecureXml.readStartElements(reader)(_.getLocalName)
    elements.nextOption() match {
      case Some(StatementRoot) => Right(Statement(body))
      case Some(ResponseRoot) => answerOf(childTexts(reader))
      case Some(other) => Left(unexpected(s"the root element is $other"))
      case None => Left(unexpected("the body has no element"))
    }
  }

  // The text of each element from the reader's position on, by name.
  private def childTexts(reader: XMLStreamReader): Map[String, String] =
    SecureXml
      .readStartElements(reader)(element => element.getLocalName -> element.getElementText.trim)
      .toMap

  private def answerOf(
    fields: Map[String, String]
  ): Either[FlexError.UnexpectedResponse, FlexResponse] =
    fields.get(ErrorCodeField) match {
      case Some(code) =>
        code.toIntOption
          .map(Refused(_, fields.getOrElse(ErrorMessageField, "")))
          .toRight(unexpected("the error code is not a number"))
      case None =>
        (fields.get(StatusField), fields.get(ReferenceCodeField)) match {
          case (Some(SuccessStatus), Some(referenceCode)) => Right(Accepted(referenceCode))
          case (status, _) =>
            Left(
              unexpected(s"the status is ${status.getOrElse("missing")}, with no reference code")
            )
        }
    }

  private def unexpected(detail: String): FlexError.UnexpectedResponse =
    FlexError.UnexpectedResponse(detail)
}
