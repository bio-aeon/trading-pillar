package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.services.analysis.AnalysisService

final class AnalyzeHandler[F[_]: Monad](tgGateway: TgGateway[F], analysis: AnalysisService[F])
    extends CommandHandler[F] {

  def command: Command = Command.Analyze

  def usage: String = "/analyze SYM - price, returns, technicals and dividends for a ticker"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
      case symRaw :: _ =>
        val ticker = Ticker(symRaw.toUpperCase)
        analysis
          .analyze(ticker)
          .flatMap(a => Replies.reply(tgGateway, chatId, AnalysisFormat.single(a)))
    }
}
