package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.market.{Source, Ticker, WatchlistItem}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.WatchlistStorage

// Single-account app for now: accountId is a constructor argument rather than derived from the
// chat. Multi-account support is a later package.
final class WatchlistHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  storage: WatchlistStorage[F],
  accountId: AccountId
) extends CommandHandler[F] {
  def command: Command = Command.Watchlist

  def usage: String = "/watchlist, /watchlist add SYM [yahoo|tinkoff] (note), or /watchlist rm SYM"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case Nil => list(chatId)
      case sub :: rest if sub.equalsIgnoreCase("add") => add(chatId, rest)
      case sub :: rest if sub.equalsIgnoreCase("rm") => remove(chatId, rest)
      case _ => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }

  private def list(chatId: tg.ChatId): F[Unit] =
    storage.byAccount(accountId).flatMap { items =>
      val text = if (items.isEmpty) "Watchlist is empty." else items.map(formatItem).mkString("\n")
      Replies.reply(tgGateway, chatId, text)
    }

  private def formatItem(item: WatchlistItem): String = {
    val noteText = item.note.filter(_.nonEmpty).fold("")(n => s" - $n")
    val disabledText = if (item.enabled) "" else " (disabled)"
    s"${item.ticker.value} (${item.source.value})$noteText$disabledText"
  }

  private def add(chatId: tg.ChatId, rest: List[String]): F[Unit] =
    rest match {
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
      case symRaw :: tail =>
        val ticker = Ticker(symRaw.toUpperCase)
        val (source, noteWords) = parseSourceAndNote(tail)
        val note = if (noteWords.isEmpty) None else Some(noteWords.mkString(" "))
        for {
          now <- Replies.nowUtc[F]
          _ <- storage.add(
            WatchlistItem(accountId, ticker, source, enabled = true, note = note, createdAt = now)
          )
          _ <- Replies.reply(
            tgGateway,
            chatId,
            s"Added ${ticker.value} (${source.value}) to the watchlist."
          )
        } yield ()
    }

  // The token right after the symbol is the source when it names one (Source.withValueOpt),
  // defaulting to Source.Yahoo; an unrecognized token is left in place as the start of the note.
  private def parseSourceAndNote(tail: List[String]): (Source, List[String]) =
    tail match {
      case sourceRaw :: noteWords =>
        Source.withValueOpt(sourceRaw) match {
          case Some(source) => (source, noteWords)
          case None => (Source.Yahoo, tail)
        }
      case Nil => (Source.Yahoo, Nil)
    }

  private def remove(chatId: tg.ChatId, rest: List[String]): F[Unit] =
    rest match {
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
      case symRaw :: _ =>
        val ticker = Ticker(symRaw.toUpperCase)
        storage.remove(accountId, ticker).flatMap { existed =>
          val text =
            if (existed) s"Removed ${ticker.value} from the watchlist."
            else s"${ticker.value} was not on the watchlist."
          Replies.reply(tgGateway, chatId, text)
        }
    }
}
