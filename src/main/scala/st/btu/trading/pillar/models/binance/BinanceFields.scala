package st.btu.trading.pillar.models.binance

import cats.syntax.traverse.*
import io.circe.{Json, JsonObject}

import java.time.format.{DateTimeFormatter, ResolverStyle}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import scala.util.Try

// Reads the fields of a Binance record, failing with the record's name when one is missing or
// malformed. A number can arrive as a JSON string or as a JSON number, so both read as text.
private[pillar] final class BinanceFields(
  source: BinanceSource,
  recordId: Option[BinanceRecordId],
  attributes: JsonObject
) {
  import BinanceFields.*

  def text(name: String): Option[String] =
    attributes(name).flatMap(textOf).map(_.trim).filter(_.nonEmpty)

  def required(name: String): Either[BinanceRecordError, String] =
    text(name).toRight(BinanceRecordError.MissingField(source, recordId, name))

  def asset(name: String): Either[BinanceRecordError, BinanceAsset] =
    required(name).map(BinanceAsset(_))

  def decimal(name: String): Either[BinanceRecordError, Option[BigDecimal]] =
    text(name) match {
      case None => Right(None)
      case Some(value) => parseDecimal(name, value).map(Some(_))
    }

  def requiredDecimal(name: String): Either[BinanceRecordError, BigDecimal] =
    required(name).flatMap(parseDecimal(name, _))

  def decimalOrZero(name: String): Either[BinanceRecordError, BigDecimal] =
    decimal(name).map(_.getOrElse(BigDecimal(0)))

  def requiredBoolean(name: String): Either[BinanceRecordError, Boolean] =
    required(name).flatMap { value =>
      value.toBooleanOption.toRight(invalid(name, value))
    }

  // The objects of a field that is a list of them, such as the details of a dust conversion.
  def objects(name: String): Either[BinanceRecordError, List[JsonObject]] =
    attributes(name).filterNot(_.isNull) match {
      case None => Left(BinanceRecordError.MissingField(source, recordId, name))
      case Some(json) =>
        json.asArray
          .flatMap(_.toList.traverse(_.asObject))
          .toRight(invalid(name, "not a list of objects"))
    }

  // An instant given as milliseconds since the epoch.
  def requiredEpochMillis(name: String): Either[BinanceRecordError, Instant] =
    required(name).flatMap { value =>
      value.toLongOption.map(Instant.ofEpochMilli).toRight(invalid(name, value))
    }

  // An instant given as a UTC date and time with no zone, such as `2019-10-12 11:12:02`.
  def requiredUtcDateTime(name: String): Either[BinanceRecordError, Instant] =
    required(name).flatMap { value =>
      Try(LocalDateTime.parse(value, UtcDateTimeFormat).toInstant(ZoneOffset.UTC)).toOption
        .toRight(invalid(name, value))
    }

  private def parseDecimal(name: String, value: String): Either[BinanceRecordError, BigDecimal] =
    Try(BigDecimal(value)).toOption.toRight(invalid(name, value))

  private def invalid(name: String, value: String): BinanceRecordError =
    BinanceRecordError.InvalidField(source, recordId, name, value)
}

private[pillar] object BinanceFields {

  private val UtcDateTimeFormat =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

  // The text a scalar value stands for: a number as the digits Binance sent, so one that exceeds
  // what a double holds is not rounded. A null, an array and an object give none.
  private def textOf(json: Json): Option[String] =
    json.fold(
      jsonNull = None,
      jsonBoolean = flag => Some(flag.toString),
      jsonNumber = number => Some(number.toString),
      jsonString = Some(_),
      jsonArray = _ => None,
      jsonObject = _ => None
    )
}
