package st.btu.trading.pillar.gateways.binance

import cats.syntax.either.*
import cats.syntax.traverse.*
import io.circe.{Json, JsonObject}
import st.btu.trading.pillar.models.binance.*

import scala.util.Try

// Reads the answers of Binance's endpoints. An answer that is not of the documented shape fails
// with `UnexpectedResponse`, naming what is wrong and never echoing the answer.
private[binance] object BinanceResponses {

  private type Reading[A] = Either[BinanceError, A]

  private val NoRecords: Reading[List[JsonObject]] = Right(Nil)

  // The fields of the answers, as Binance names them.
  private object Field {
    // The `code` of an answer, which is a negative number in an error and a string in the envelope
    // of a fiat answer.
    val Code = "code"
    val ErrorMessage = "msg"
    val FiatMessage = "message"
    val FiatData = "data"
    val ServerTime = "serverTime"
    val EnableReading = "enableReading"
    val IpRestrict = "ipRestrict"
    val Symbols = "symbols"
    val Symbol = "symbol"
    val BaseAsset = "baseAsset"
    val QuoteAsset = "quoteAsset"
    val Coin = "coin"
    val Name = "name"
    val ConvertList = "list"
    val MoreData = "moreData"
  }

  // Binance's own marker of a successful fiat answer, in the `code` of its envelope.
  private val FiatSuccessCode = "000000"

  // The position of a bar's close in the array Binance gives for the bar.
  private val CloseIndex = 4

  // The permission flags that do not give the key more than reading: reading itself, and reading
  // over the FIX protocol.
  private val ReadingFlags = Set(Field.EnableReading, "enableFixReadOnly")

  private val PermissionFlagPrefixes = List("enable", "permits")

  /** What a parsed answer says when it is an error: a negative `code` and a `msg`, as Binance
    * reports errors, which also come with a success status from some endpoints.
    */
  def refusalIn(json: Json): Option[(Int, String)] =
    for {
      body <- json.asObject
      code <- body(Field.Code).flatMap(_.asNumber).flatMap(_.toInt).filter(_ < 0)
      message <- body(Field.ErrorMessage).flatMap(_.asString)
    } yield (code, message)

  def serverTime(json: Json): Reading[Long] =
    for {
      body <- objectOf(json, "the server time")
      time <- body(Field.ServerTime)
        .flatMap(_.asNumber)
        .flatMap(_.toLong)
        .toRight(unexpected("the server time has no serverTime"))
    } yield time

  def permissions(json: Json): Reading[BinanceKeyPermissions] =
    for {
      body <- objectOf(json, "the key restrictions")
      reading <- body(Field.EnableReading)
        .flatMap(_.asBoolean)
        .toRight(unexpected("the key restrictions have no enableReading"))
      ipRestricted = body(Field.IpRestrict).flatMap(_.asBoolean).getOrElse(false)
      others = body.toList.collect {
        case (name, value) if isPermissionFlag(name) => value.asBoolean.map(name -> _)
      }.flatten
    } yield BinanceKeyPermissions(ipRestricted, reading, others.toMap)

  def pairs(json: Json): Reading[List[BinancePair]] =
    for {
      body <- objectOf(json, "the exchange information")
      symbols <- body(Field.Symbols)
        .flatMap(_.asArray)
        .toRight(unexpected("the exchange information has no symbols"))
      pairs <- symbols.toList.traverse(pairOf)
    } yield pairs

  def delisted(json: Json): Reading[List[BinanceSymbol]] =
    for {
      entries <- arrayOf(json, "the delist schedule")
      symbols <- entries.traverse { entry =>
        for {
          body <- objectOf(entry, "an entry of the delist schedule")
          named <- body(Field.Symbols)
            .flatMap(_.asArray)
            .toRight(unexpected("an entry of the delist schedule has no symbols"))
          symbols <- named.toList.traverse(stringOf(_, "a delisted symbol").map(BinanceSymbol(_)))
        } yield symbols
      }
    } yield symbols.flatten.distinct

  def coinNames(json: Json): Reading[Map[BinanceAsset, String]] =
    for {
      entries <- arrayOf(json, "the coin list")
      named <- entries.traverse { entry =>
        for {
          body <- objectOf(entry, "a coin")
          coin <- field(body, Field.Coin, "a coin")
          name <- field(body, Field.Name, "a coin")
        } yield BinanceAsset(coin) -> name
      }
    } yield named.toMap

  /** The JSON objects of an answer that is an array of them. */
  def objects(json: Json): Reading[List[JsonObject]] =
    arrayOf(json, "the answer").flatMap(_.traverse(objectOf(_, "a record")))

  /** The records of a fiat answer, which comes in an envelope whose `data` is left out when there
    * is none. An envelope whose `code` is not Binance's success code is a refusal.
    */
  def fiatRecords(json: Json): Reading[List[JsonObject]] =
    for {
      envelope <- objectOf(json, "the fiat answer")
      _ <- envelope(Field.Code)
        .flatMap(_.asString)
        .filter(_ != FiatSuccessCode)
        .fold[Reading[Unit]](Right(())) { code =>
          Left(
            BinanceError.Refused(
              code.toIntOption.getOrElse(0),
              envelope(Field.FiatMessage).flatMap(_.asString).getOrElse("no message")
            )
          )
        }
      records <- envelope(Field.FiatData).filterNot(_.isNull).fold(NoRecords)(objects)
    } yield records

  /** One answer of Convert: its orders, and whether there are more of them than the answer holds.
    */
  final case class ConvertPage(orders: List[JsonObject], moreData: Boolean)

  def convertPage(json: Json): Reading[ConvertPage] =
    for {
      body <- objectOf(json, "the Convert answer")
      orders <- body(Field.ConvertList).filterNot(_.isNull).fold(NoRecords)(objects)
    } yield ConvertPage(orders, body(Field.MoreData).flatMap(_.asBoolean).getOrElse(false))

  /** The close of the bar an answer of `klines` holds, or none when it holds no bar. */
  def close(json: Json): Reading[Option[BigDecimal]] =
    for {
      bars <- arrayOf(json, "the bar list")
      close <- bars.headOption match {
        case None => None.asRight
        case Some(bar) =>
          for {
            values <- arrayOf(bar, "a bar")
            text <- values
              .lift(CloseIndex)
              .flatMap(value => value.asString.orElse(value.asNumber.map(_.toString)))
              .toRight(unexpected("a bar has no close"))
            closing <- Try(BigDecimal(text)).toEither.leftMap(_ =>
              unexpected("a close is no number")
            )
          } yield Some(closing)
      }
    } yield close

  private def isPermissionFlag(name: String): Boolean =
    PermissionFlagPrefixes.exists(name.startsWith) && !ReadingFlags(name)

  private def pairOf(entry: Json): Reading[BinancePair] =
    for {
      body <- objectOf(entry, "a symbol of the exchange information")
      symbol <- field(body, Field.Symbol, "a symbol of the exchange information")
      base <- field(body, Field.BaseAsset, "a symbol of the exchange information")
      quote <- field(body, Field.QuoteAsset, "a symbol of the exchange information")
    } yield BinancePair(BinanceSymbol(symbol), BinanceAsset(base), BinanceAsset(quote))

  private def field(body: JsonObject, name: String, of: String): Reading[String] =
    body(name).toRight(unexpected(s"$of has no $name")).flatMap(stringOf(_, s"the $name of $of"))

  private def stringOf(json: Json, of: String): Reading[String] =
    json.asString.toRight(unexpected(s"$of is not a string"))

  private def objectOf(json: Json, of: String): Reading[JsonObject] =
    json.asObject.toRight(unexpected(s"$of is not an object"))

  private def arrayOf(json: Json, of: String): Reading[List[Json]] =
    json.asArray.map(_.toList).toRight(unexpected(s"$of is not an array"))

  private def unexpected(detail: String): BinanceError = BinanceError.UnexpectedResponse(detail)
}
