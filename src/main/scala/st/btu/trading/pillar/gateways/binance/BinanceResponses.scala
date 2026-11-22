package st.btu.trading.pillar.gateways.binance

import cats.syntax.either.*
import cats.syntax.traverse.*
import io.circe.{Json, JsonObject}
import st.btu.trading.pillar.models.binance.*

import java.time.{Instant, LocalDate, ZoneOffset}
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
    val Rows = "rows"
    val UserAssetDribblets = "userAssetDribblets"
    val Balances = "balances"
    val Asset = "asset"
    val Free = "free"
    val Locked = "locked"
    val Freeze = "freeze"
    val Withdrawing = "withdrawing"
    val Price = "price"
  }

  // Binance's own marker of a successful fiat answer, in the `code` of its envelope.
  private val FiatSuccessCode = "000000"

  // The positions of the values of a bar in the array Binance gives for the bar.
  private object BarIndex {
    val OpenTime = 0
    val Open = 1
    val High = 2
    val Low = 3
    val Close = 4
    val Volume = 5
    val CloseTime = 6
  }

  private val Zero = BigDecimal(0)

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

  /** The rows of an answer of the asset dividend log, an object whose `rows` is left out when there
    * is none.
    */
  def distributions(json: Json): Reading[List[JsonObject]] =
    listedIn(json, "the distribution answer", Field.Rows)

  /** The conversions of an answer of the dust log, an object whose `userAssetDribblets` is left out
    * when there is none.
    */
  def dustConversions(json: Json): Reading[List[JsonObject]] =
    listedIn(json, "the dust answer", Field.UserAssetDribblets)

  /** The close of the bar an answer of `klines` holds, or none when it holds no bar. */
  def close(json: Json): Reading[Option[BigDecimal]] =
    for {
      bars <- arrayOf(json, "the bar list")
      close <- bars.headOption.traverse { bar =>
        arrayOf(bar, "a bar").flatMap(decimalAt(_, BarIndex.Close, "close"))
      }
    } yield close

  /** The bars an answer of `klines` holds, as daily bars, in the order Binance lists them. */
  def dailyBars(json: Json): Reading[List[BinanceDailyBar]] =
    arrayOf(json, "the bar list").flatMap(_.traverse(dailyBarOf))

  /** The price an answer of the ticker price holds. */
  def price(json: Json): Reading[BigDecimal] =
    objectOf(json, "the ticker price").flatMap(decimal(_, Field.Price, "the ticker price"))

  /** The amount of each asset in the balances of an answer of the spot account, free and locked
    * together, leaving out an asset with none.
    */
  def spotBalances(json: Json): Reading[Map[BinanceAsset, BigDecimal]] =
    for {
      body <- objectOf(json, "the spot account")
      balances <- body(Field.Balances)
        .flatMap(_.asArray)
        .toRight(unexpected("the spot account has no balances"))
      assets <- balances.toList.traverse(balanceOf(_, List(Field.Free, Field.Locked)))
    } yield held(assets)

  /** The amount of each asset in an answer of the funding wallet, free, locked, frozen and
    * withdrawing together, leaving out an asset with none.
    */
  def fundingBalances(json: Json): Reading[Map[BinanceAsset, BigDecimal]] =
    for {
      entries <- arrayOf(json, "the funding wallet")
      assets <- entries.traverse(
        balanceOf(_, List(Field.Free, Field.Locked, Field.Freeze, Field.Withdrawing))
      )
    } yield held(assets)

  private def listedIn(json: Json, of: String, name: String): Reading[List[JsonObject]] =
    objectOf(json, of).flatMap(_(name).filterNot(_.isNull).fold(NoRecords)(objects))

  private def isPermissionFlag(name: String): Boolean =
    PermissionFlagPrefixes.exists(name.startsWith) && !ReadingFlags(name)

  private def pairOf(entry: Json): Reading[BinancePair] =
    for {
      body <- objectOf(entry, "a symbol of the exchange information")
      symbol <- field(body, Field.Symbol, "a symbol of the exchange information")
      base <- field(body, Field.BaseAsset, "a symbol of the exchange information")
      quote <- field(body, Field.QuoteAsset, "a symbol of the exchange information")
    } yield BinancePair(BinanceSymbol(symbol), BinanceAsset(base), BinanceAsset(quote))

  // The asset of an entry of a balance list and the sum of the amounts it holds in `amounts`.
  private def balanceOf(entry: Json, amounts: List[String]): Reading[(BinanceAsset, BigDecimal)] =
    for {
      body <- objectOf(entry, "a balance")
      asset <- field(body, Field.Asset, "a balance")
      parts <- amounts.traverse(decimal(body, _, "a balance"))
    } yield BinanceAsset(asset) -> parts.sum

  // The amount held of each asset, with an asset that appears twice added up and one with none left
  // out.
  private def held(balances: List[(BinanceAsset, BigDecimal)]): Map[BinanceAsset, BigDecimal] =
    balances
      .groupMapReduce { case (asset, _) => asset } { case (_, amount) => amount }(_ + _)
      .filter { case (_, amount) => amount != Zero }

  private def dailyBarOf(entry: Json): Reading[BinanceDailyBar] =
    for {
      values <- arrayOf(entry, "a bar")
      openTime <- instantAt(values, BarIndex.OpenTime, "open time")
      open <- decimalAt(values, BarIndex.Open, "open")
      high <- decimalAt(values, BarIndex.High, "high")
      low <- decimalAt(values, BarIndex.Low, "low")
      close <- decimalAt(values, BarIndex.Close, "close")
      volume <- decimalAt(values, BarIndex.Volume, "volume")
      closeTime <- instantAt(values, BarIndex.CloseTime, "close time")
    } yield BinanceDailyBar(
      LocalDate.ofInstant(openTime, ZoneOffset.UTC),
      open,
      high,
      low,
      close,
      volume,
      closeTime
    )

  // The text of a number, which Binance writes as a JSON string and sometimes as a JSON number.
  private def numberText(json: Json): Option[String] =
    json.asString.orElse(json.asNumber.map(_.toString))

  private def parseDecimal(text: String, of: String): Reading[BigDecimal] =
    Try(BigDecimal(text)).toEither.leftMap(_ => unexpected(s"$of is no number"))

  private def decimal(body: JsonObject, name: String, of: String): Reading[BigDecimal] =
    body(name)
      .flatMap(numberText)
      .toRight(unexpected(s"$of has no $name"))
      .flatMap(parseDecimal(_, s"the $name of $of"))

  private def decimalAt(values: List[Json], index: Int, name: String): Reading[BigDecimal] =
    values
      .lift(index)
      .flatMap(numberText)
      .toRight(unexpected(s"a bar has no $name"))
      .flatMap(parseDecimal(_, s"the $name of a bar"))

  private def instantAt(values: List[Json], index: Int, name: String): Reading[Instant] =
    values
      .lift(index)
      .flatMap(_.asNumber)
      .flatMap(_.toLong)
      .map(Instant.ofEpochMilli)
      .toRight(unexpected(s"a bar has no $name"))

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
