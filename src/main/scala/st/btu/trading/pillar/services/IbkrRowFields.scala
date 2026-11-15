package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.ibkr.IbkrAttributes.Name

import java.time.{Instant, ZoneId}

// Reads the attributes of a statement row, failing with the row's name when one is missing or
// malformed.
private[services] final class IbkrRowFields(row: IbkrStatementRow) {
  import IbkrRowFields.*

  private val id = Some(row.transactionId)

  def text(name: String): Option[String] = row.attribute(name)

  def required(name: String): Either[IbkrStatementError, String] =
    text(name).toRight(IbkrStatementError.MissingAttribute(row.section, id, name))

  def currency(name: String): Either[IbkrStatementError, Currency] =
    required(name).map(Currency(_))

  def decimal(name: String): Either[IbkrStatementError, Option[BigDecimal]] =
    text(name) match {
      case None => Right(None)
      case Some(value) =>
        IbkrAttributes
          .decimal(value)
          .map(Some(_))
          .toRight(IbkrStatementError.InvalidAttribute(row.section, id, name, value))
    }

  def requiredDecimal(name: String): Either[IbkrStatementError, BigDecimal] =
    decimal(name).flatMap(_.toRight(IbkrStatementError.MissingAttribute(row.section, id, name)))

  def decimalOrZero(name: String): Either[IbkrStatementError, BigDecimal] =
    decimal(name).map(_.getOrElse(BigDecimal(0)))

  // The price per unit, where the row has one other than zero.
  def price(name: String): Either[IbkrStatementError, Option[BigDecimal]] =
    decimal(name).map(_.filter(_.signum != 0))

  def instrument: Option[InstrumentKey] = text(Name.Conid).map(instrumentOf)

  def requiredInstrument: Either[IbkrStatementError, InstrumentKey] =
    required(Name.Conid).map(instrumentOf)

  def occurredAt(zone: ZoneId): Either[IbkrStatementError, Instant] =
    OccurrenceAttributes.flatMap(name => text(name).map(name -> _)).headOption match {
      case Some((name, value)) =>
        IbkrAttributes
          .dateTime(value)
          .toRight(IbkrStatementError.InvalidAttribute(row.section, id, name, value))
          .map(_.atZone(zone).toInstant)
      case None => Right(row.reportDate.atStartOfDay(zone).toInstant)
    }
}

private[services] object IbkrRowFields {

  // The attributes that date a row, in the order they are tried. The report date dates a row that
  // carries none of them.
  private val OccurrenceAttributes = List(Name.DateTime, Name.Date, Name.TradeDate)

  def instrumentOf(conid: String): InstrumentKey =
    InstrumentKey(Broker.Ibkr, BrokerInstrumentId(conid))
}
