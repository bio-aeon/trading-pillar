package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.gateways.news.{NewsItem, NewsSource}
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg

import scala.util.Try

final class NewsHandler[F[_]: Monad](tgGateway: TgGateway[F], news: NewsSource[F])
    extends CommandHandler[F] {

  private val DefaultCount = 5
  private val MaxCount = 10

  def command: Command = Command.News

  def usage: String = "/news SYM (n, default 5, max 10) - recent news for a ticker"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
      case symRaw :: rest =>
        val ticker = Ticker(symRaw.toUpperCase)
        val count = parseCount(rest.headOption)
        news.news(ticker, count).flatMap(items => Replies.reply(tgGateway, chatId, format(items)))
    }

  private def parseCount(raw: Option[String]): Int =
    raw.flatMap(s => Try(s.toInt).toOption).filter(_ > 0).getOrElse(DefaultCount).min(MaxCount)

  private def format(items: List[NewsItem]): String =
    if (items.isEmpty) "No news found." else items.map(formatItem).mkString("\n")

  private def formatItem(item: NewsItem): String = {
    val publisher = item.publisher.getOrElse("?")
    val date = item.publishedAt.fold("?")(_.format(Replies.TimeFormatter))
    s"${item.title} - $publisher ($date) ${item.link}"
  }
}
