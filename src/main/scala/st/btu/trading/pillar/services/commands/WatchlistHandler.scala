package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.foldable.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.gateways.{MarketDataSources, TgGateway}
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.market.{SourceSetting, Ticker, TickerSource, WatchlistItem}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.{TickerSourceStorage, WatchlistStorage}

// Single-account app for now: accountId is a constructor argument rather than derived from the
// chat. Multi-account support is a later package.
final class WatchlistHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  storage: WatchlistStorage[F],
  settings: TickerSourceStorage[F],
  sources: MarketDataSources[F],
  accountId: AccountId
) extends CommandHandler[F] {
  def command: Command = Command.Watchlist

  def usage: String = "/watchlist, /watchlist add SYM (yahoo|tinkoff) (note), or /watchlist rm SYM"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case Nil => list(chatId)
      case sub :: rest if sub.equalsIgnoreCase("add") => add(chatId, rest)
      case sub :: rest if sub.equalsIgnoreCase("rm") => remove(chatId, rest)
      case _ => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }

  private def list(chatId: tg.ChatId): F[Unit] =
    for {
      items <- storage.byAccount(accountId)
      lines <- items.traverse(formatItem)
      text = if (lines.isEmpty) "Watchlist is empty." else lines.mkString("\n")
      _ <- Replies.reply(tgGateway, chatId, text)
    } yield ()

  private def formatItem(item: WatchlistItem): F[String] =
    sources.settingFor(item.ticker).map { setting =>
      val noteText = item.note.filter(_.nonEmpty).fold("")(n => s" - $n")
      val disabledText = if (item.enabled) "" else " (disabled)"
      s"${item.ticker.value} (${SourceSettings.describe(setting)})$noteText$disabledText"
    }

  private def add(chatId: tg.ChatId, rest: List[String]): F[Unit] =
    rest match {
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
      case symRaw :: tail =>
        val ticker = Ticker(symRaw.toUpperCase)
        val (requested, noteWords) = parseSourceAndNote(tail)
        val note = if (noteWords.isEmpty) None else Some(noteWords.mkString(" "))
        for {
          now <- Replies.nowUtc[F]
          _ <- storage.add(
            WatchlistItem(accountId, ticker, enabled = true, note = note, createdAt = now)
          )
          _ <- requested.traverse_(assign(ticker, _))
          effective <- sources.settingFor(ticker)
          _ <- Replies.reply(
            tgGateway,
            chatId,
            s"Added ${ticker.value} (${SourceSettings.describe(effective)}) to the watchlist."
          )
        } yield ()
    }

  // The token right after the symbol selects the ticker's source when it names one; any other token
  // is left in place as the start of the note.
  private def parseSourceAndNote(tail: List[String]): (Option[SourceSetting], List[String]) =
    tail match {
      case token :: noteWords =>
        SourceSettings.parse(token) match {
          case Some(requested) => (Some(requested), noteWords)
          case None => (None, tail)
        }
      case Nil => (None, Nil)
    }

  private def assign(ticker: Ticker, requested: SourceSetting): F[Unit] =
    sources
      .settingFor(ticker)
      .flatMap(current =>
        settings.upsert(TickerSource(ticker, keepingBoardOrSymbol(requested, current)))
      )

  // Asking for the source a ticker already resolves to, whether set for it or the router's
  // default, leaves its Tinkoff board or Yahoo symbol in place.
  private def keepingBoardOrSymbol(
    requested: SourceSetting,
    current: SourceSetting
  ): SourceSetting =
    if (requested.source == current.source) current else requested

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
