package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import st.btu.trading.pillar.gateways.{MarketDataSources, TgGateway}
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.market.{SourceSetting, Ticker, TickerSource}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.TickerSourceStorage

/** Shows and changes which market data source serves a ticker. A ticker's source is shared by every
  * account, so unlike the watchlist this handler is not tied to an account.
  */
final class SourceHandler[F[_]: Monad](
  tgGateway: TgGateway[F],
  settings: TickerSourceStorage[F],
  sources: MarketDataSources[F]
) extends CommandHandler[F] {

  def command: Command = Command.Source

  def usage: String =
    "/source, /source SYM, /source SYM yahoo (SYMBOL), /source SYM tinkoff (BOARD), or " +
      "/source SYM default"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case Nil => list(chatId)
      case symRaw :: Nil => show(chatId, tickerOf(symRaw))
      case symRaw :: words => choose(chatId, tickerOf(symRaw), words)
    }

  private def tickerOf(symRaw: String): Ticker = Ticker(symRaw.toUpperCase)

  private def list(chatId: tg.ChatId): F[Unit] =
    settings.all.flatMap { entries =>
      val text =
        if (entries.isEmpty) "No ticker has a source of its own; every ticker uses the default."
        else
          entries
            .sortBy(_.ticker.value)
            .map(entry => s"${entry.ticker.value} - ${SourceSettings.describe(entry.setting)}")
            .mkString("\n")
      Replies.reply(tgGateway, chatId, text)
    }

  private def show(chatId: tg.ChatId, ticker: Ticker): F[Unit] =
    settings.find(ticker).flatMap {
      case Some(entry) =>
        Replies.reply(
          tgGateway,
          chatId,
          s"${ticker.value} uses ${SourceSettings.describe(entry.setting)}."
        )
      case None =>
        sources.settingFor(ticker).flatMap { setting =>
          Replies.reply(
            tgGateway,
            chatId,
            s"${ticker.value} uses the default: ${SourceSettings.describe(setting)}."
          )
        }
    }

  private def choose(chatId: tg.ChatId, ticker: Ticker, words: List[String]): F[Unit] =
    SourceSettings.parseChoice(words) match {
      case Some(SourceSettings.Choice.Reset) => reset(chatId, ticker)
      case Some(SourceSettings.Choice.Assign(setting)) => assign(chatId, ticker, setting)
      case None => usageReply(chatId)
    }

  private def assign(chatId: tg.ChatId, ticker: Ticker, setting: SourceSetting): F[Unit] =
    settings.upsert(TickerSource(ticker, setting)) >>
      Replies.reply(
        tgGateway,
        chatId,
        s"${ticker.value} now uses ${SourceSettings.describe(setting)}."
      )

  private def reset(chatId: tg.ChatId, ticker: Ticker): F[Unit] =
    settings.delete(ticker).flatMap { removed =>
      val text =
        if (removed) s"${ticker.value} now uses the default source."
        else s"${ticker.value} already uses the default source."
      Replies.reply(tgGateway, chatId, text)
    }

  private def usageReply(chatId: tg.ChatId): F[Unit] =
    Replies.reply(tgGateway, chatId, s"Usage: $usage")
}
