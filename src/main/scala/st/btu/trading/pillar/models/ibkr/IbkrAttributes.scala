package st.btu.trading.pillar.models.ibkr

import java.time.format.{DateTimeFormatter, ResolverStyle}
import java.time.{LocalDate, LocalDateTime}
import scala.util.Try

// How the attributes of a statement row are named and their values read. The formats are those the
// Flex Query is set up to produce: dates as yyyyMMdd, and a date with a time as yyyyMMdd;HHmmss.
private[pillar] object IbkrAttributes {

  // The attributes of the elements of a statement that the library reads, as IB names them.
  object Name {
    val AssetCategory = "assetCategory"
    val Amount = "amount"
    val BuySell = "buySell"
    val CashTransfer = "cashTransfer"
    val Conid = "conid"
    val Currency = "currency"
    val Date = "date"
    val DateTime = "dateTime"
    val Description = "description"
    val Direction = "direction"
    val IbCommission = "ibCommission"
    val IbCommissionCurrency = "ibCommissionCurrency"
    val Isin = "isin"
    val LevelOfDetail = "levelOfDetail"
    val ListingExchange = "listingExchange"
    val Notes = "notes"
    val OrigTransactionId = "origTransactionID"
    val Proceeds = "proceeds"
    val Quantity = "quantity"
    val ReportDate = "reportDate"
    val Symbol = "symbol"
    val Taxes = "taxes"
    val TradeDate = "tradeDate"
    val TradePrice = "tradePrice"
    val TransactionId = "transactionID"
    val TransactionType = "transactionType"
    val TransferPrice = "transferPrice"
    val Type = "type"
  }

  // The asset categories of IB that the library tells apart.
  object Category {
    val Stock = "STK"
    val Cash = "CASH"
  }

  // What IB sends for an attribute that has no value.
  private val NoValue = Set("", "-", "--", "N/A")

  private val ThousandsSeparator = ","

  private val DateFormat =
    DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)

  private val DateTimeFormat =
    DateTimeFormatter.ofPattern("uuuuMMdd;HHmmss").withResolverStyle(ResolverStyle.STRICT)

  // The value of attribute `name`, or None when it is missing or stands for no value.
  def value(attributes: Map[String, String], name: String): Option[String] =
    attributes.get(name).map(_.trim).filterNot(NoValue)

  def date(text: String): Option[LocalDate] =
    Try(LocalDate.parse(text, DateFormat)).toOption

  // A date alone stands for the start of that day.
  def dateTime(text: String): Option[LocalDateTime] =
    Try(LocalDateTime.parse(text, DateTimeFormat)).toOption.orElse(date(text).map(_.atStartOfDay))

  // A number that may carry thousands separators.
  def decimal(text: String): Option[BigDecimal] =
    Try(BigDecimal(text.replace(ThousandsSeparator, ""))).toOption
}
