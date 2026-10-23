package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import enumeratum.values.*
import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.gateways.{MarketDataSources, TgGateway}
import st.btu.trading.pillar.models.domain.{AccountId, Command, TcsPortfolioOperation}
import st.btu.trading.pillar.models.market.DividendEvent
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.{
  DividendStorage,
  PortfolioValuationStorage,
  TcsPortfolioOperationStorage,
  WatchlistStorage
}

import java.nio.charset.StandardCharsets
import java.time.{LocalDate, ZoneOffset, ZonedDateTime}
import scala.util.Try

// Single-account app for now, same as every other handler: accountId is a constructor argument
// rather than derived from the chat. Dividends are read from the source each ticker is configured
// to use.
final class ExportHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  operations: TcsPortfolioOperationStorage[F],
  valuations: PortfolioValuationStorage[F],
  dividends: DividendStorage[F],
  watchlist: WatchlistStorage[F],
  sources: MarketDataSources[F],
  accountId: AccountId
) extends CommandHandler[F] {

  import ExportHandler.*

  def command: Command = Command.Export

  def usage: String =
    "/export operations|valuations|dividends (year, default current year) - download a CSV file"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case kindRaw :: rest =>
        Kind.parse(kindRaw) match {
          case Some(kind) =>
            resolveYear(rest.headOption).flatMap(year => exportKind(chatId, kind, year))
          case None => Replies.reply(tgGateway, chatId, s"Usage: $usage")
        }
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }

  private def exportKind(chatId: tg.ChatId, kind: Kind, year: Int): F[Unit] =
    kind match {
      case Kind.Operations => exportOperations(chatId, year)
      case Kind.Valuations => exportValuations(chatId, year)
      case Kind.Dividends => exportDividends(chatId, year)
    }

  private def exportOperations(chatId: tg.ChatId, year: Int): F[Unit] = {
    val since = ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    val until = ZonedDateTime.of(year + 1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    operations.operationsByDtRange(accountId, since, until).flatMap { ops =>
      val rows = ops.toList.sortBy(_.createdAt.toInstant.toEpochMilli).map(operationRow)
      sendOrEmpty(chatId, Kind.Operations, year, OperationsHeader, rows)
    }
  }

  private def exportValuations(chatId: tg.ChatId, year: Int): F[Unit] = {
    val from = LocalDate.of(year, 1, 1)
    val to = LocalDate.of(year, 12, 31)
    valuations.series(accountId, from, to).flatMap { points =>
      sendOrEmpty(chatId, Kind.Valuations, year, ValuationsHeader, points.toList.map(valuationRow))
    }
  }

  private def exportDividends(chatId: tg.ChatId, year: Int): F[Unit] = {
    val from = LocalDate.of(year, 1, 1)
    val to = LocalDate.of(year, 12, 31)
    for {
      items <- watchlist.byAccount(accountId)
      tickers <- items.traverse(item => MarketDataSources.sourced(sources, item.ticker))
      perTicker <- tickers.traverse(t => dividends.byTicker(t.ticker, t.source, from, to))
      rows = perTicker.flatten.sortBy(_.exDate.toEpochDay).map(dividendRow)
      _ <- sendOrEmpty(chatId, Kind.Dividends, year, DividendsHeader, rows)
    } yield ()
  }

  private def sendOrEmpty(
    chatId: tg.ChatId,
    kind: Kind,
    year: Int,
    header: List[String],
    rows: List[List[String]]
  ): F[Unit] =
    if (rows.isEmpty) Replies.reply(tgGateway, chatId, s"No ${kind.value} to export for $year.")
    else {
      val bytes = Csv.write(header, rows).getBytes(StandardCharsets.UTF_8)
      tgGateway.sendDocument(chatId, s"${kind.value}-$year.csv", bytes, "text/csv", None)
    }

  private def operationRow(op: TcsPortfolioOperation): List[String] =
    List(
      op.createdAt.toString,
      op.operationType.value,
      op.figi.fold("")(_.value),
      op.currency.value,
      op.price.toString,
      op.quantity.toString,
      op.amount.toString,
      op.status.value
    )

  private def valuationRow(p: ValuationPoint): List[String] =
    List(p.date.toString, p.value.toString, p.invested.toString, p.cashFlow.toString)

  private def dividendRow(e: DividendEvent): List[String] =
    List(e.ticker.value, e.exDate.toString, e.amount.toString, e.currency.fold("")(_.value))

  private def resolveYear(arg: Option[String]): F[Int] =
    arg.flatMap(a => Try(a.toInt).toOption) match {
      case Some(y) => Monad[F].pure(y)
      case None => Clock[F].realTimeInstant.map(LocalDate.ofInstant(_, ZoneOffset.UTC).getYear)
    }
}

object ExportHandler {

  private val OperationsHeader =
    List(
      "created_at",
      "operation_type",
      "figi",
      "currency",
      "price",
      "quantity",
      "amount",
      "status"
    )
  private val ValuationsHeader = List("date", "value", "invested", "cash_flow")
  private val DividendsHeader = List("ticker", "ex_date", "amount", "currency")

  sealed abstract class Kind(val value: String) extends StringEnumEntry

  object Kind extends StringEnum[Kind] {
    case object Operations extends Kind("operations")
    case object Valuations extends Kind("valuations")
    case object Dividends extends Kind("dividends")

    val values: IndexedSeq[Kind] = findValues

    // Case-insensitive: every value above is already lowercase, so lowercasing the input first
    // and looking it up exactly is equivalent to a case-insensitive match against it.
    def parse(s: String): Option[Kind] = withValueOpt(s.toLowerCase)
  }
}
