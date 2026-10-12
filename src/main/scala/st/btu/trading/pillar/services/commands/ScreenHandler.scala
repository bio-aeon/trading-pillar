package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.gateways.screener.{PredefinedScreen, Screener, ScreenerRow}
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg

import scala.util.Try

final class ScreenHandler[F[_]: Monad](tgGateway: TgGateway[F], screener: Screener[F])
    extends CommandHandler[F] {

  private val DefaultCount = 10
  private val MaxCount = 25

  def command: Command = Command.Screen

  def usage: String = "/screen gainers|losers|active (n, default 10, max 25) - predefined screener"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
      case screenRaw :: rest =>
        PredefinedScreen.withValueOpt(screenRaw.toLowerCase) match {
          case Some(screen) =>
            val count = parseCount(rest.headOption)
            screener
              .predefined(screen, count)
              .flatMap(rows => Replies.reply(tgGateway, chatId, format(rows)))
          case None => Replies.reply(tgGateway, chatId, s"Usage: $usage")
        }
    }

  private def parseCount(raw: Option[String]): Int =
    raw.flatMap(s => Try(s.toInt).toOption).filter(_ > 0).getOrElse(DefaultCount).min(MaxCount)

  private def format(rows: List[ScreenerRow]): String =
    if (rows.isEmpty) "No results." else rows.map(formatRow).mkString("\n")

  private def formatRow(r: ScreenerRow): String = {
    val name = r.name.getOrElse("?")
    val price = r.price.fold("?")(Replies.scaled)
    val change = r.changePct.fold("?")(c => s"${Replies.scaled(c)}%")
    s"${r.ticker.value} $name $price $change"
  }
}
