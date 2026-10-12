package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.services.analysis.AnalysisService

final class CompareHandler[F[_]: Monad](tgGateway: TgGateway[F], analysis: AnalysisService[F])
    extends CommandHandler[F] {

  def command: Command = Command.Compare

  def usage: String = "/compare SYM1 SYM2 - compare two tickers side by side"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case aRaw :: bRaw :: _ =>
        val tickerA = Ticker(aRaw.toUpperCase)
        val tickerB = Ticker(bRaw.toUpperCase)
        for {
          a <- analysis.analyze(tickerA)
          b <- analysis.analyze(tickerB)
          _ <- Replies.reply(tgGateway, chatId, AnalysisFormat.sideBySide(a, b))
        } yield ()
      case _ => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }
}
