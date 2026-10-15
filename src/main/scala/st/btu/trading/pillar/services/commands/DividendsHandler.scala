package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.gateways.{MarketDataSources, TgGateway}
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.market.{DividendEvent, SourcedTicker, Ticker}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.{DividendStorage, WatchlistStorage}

import java.time.LocalDate

// Single-account app for now: accountId is a constructor argument rather than derived from the
// chat, same as WatchlistHandler. Each ticker's events are read from the source it is configured
// to use, so events another source stored for it are left out.
final class DividendsHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  dividends: DividendStorage[F],
  watchlist: WatchlistStorage[F],
  sources: MarketDataSources[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Dividends

  def usage: String =
    "/dividends - upcoming ex-dates and trailing 12-month totals for the watchlist"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    for {
      items <- watchlist.byAccount(accountId)
      tickers <- items.traverse(item => MarketDataSources.sourced(sources, item.ticker))
      today <- Replies.todayUtc[F]
      upcoming <- dividends.upcoming(tickers, today)
      totals <- tickers.traverse(totalFor(_, today))
      _ <- Replies.reply(tgGateway, chatId, format(upcoming, totals.flatten))
    } yield ()

  private def totalFor(sourced: SourcedTicker, today: LocalDate): F[Option[(Ticker, BigDecimal)]] =
    dividends.byTicker(sourced.ticker, sourced.source, today.minusYears(1), today).map { events =>
      Option.when(events.nonEmpty)(sourced.ticker -> events.map(_.amount).sum)
    }

  private def format(upcoming: List[DividendEvent], totals: List[(Ticker, BigDecimal)]): String = {
    val upcomingLines =
      if (upcoming.isEmpty) List("No upcoming ex-dividend dates.")
      else "Upcoming ex-dates:" :: upcoming.map(formatUpcoming)
    val totalLines =
      if (totals.isEmpty) List("No dividends in the trailing 12 months.")
      else "Trailing 12 months:" :: totals.map(formatTotal)
    (upcomingLines ++ ("" :: totalLines)).mkString("\n")
  }

  private def formatUpcoming(e: DividendEvent): String = {
    val currencySuffix = e.currency.fold("")(c => s" ${c.value}")
    s"${e.ticker.value} ${e.exDate} ${Replies.scaled(e.amount)}$currencySuffix"
  }

  private def formatTotal(t: (Ticker, BigDecimal)): String =
    s"${t._1.value}: ${Replies.scaled(t._2)}"
}
