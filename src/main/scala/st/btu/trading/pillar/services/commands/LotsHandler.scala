package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.tax.{Lot, TaxReport, TaxService}

import java.time.temporal.ChronoUnit
import java.time.LocalDate

// Single-account app for now, same as every other handler: accountId is a constructor argument
// rather than derived from the chat.
final class LotsHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  taxService: TaxService[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Lots

  def usage: String = "/lots SYM - open FIFO tax lots for a ticker"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case symRaw :: _ =>
        val ticker = Ticker(symRaw.toUpperCase)
        for {
          today <- Replies.todayUtc[F]
          lots <- taxService.openLots(accountId, ticker)
          _ <- Replies.reply(tgGateway, chatId, format(ticker, lots, today))
        } yield ()
      case Nil => Replies.reply(tgGateway, chatId, s"Usage: $usage")
    }

  private def format(ticker: Ticker, lots: List[Lot], today: LocalDate): String =
    if (lots.isEmpty) s"No open lots for ${ticker.value}."
    else lots.map(formatLot(_, today)).mkString("\n")

  private def formatLot(lot: Lot, today: LocalDate): String = {
    val holdingDays = ChronoUnit.DAYS.between(lot.acquiredOn, today)
    val longTermSuffix =
      if (holdingDays >= taxService.config.longTermYears.toLong * TaxReport.DaysPerYear)
        ", long-term"
      else ""
    s"${lot.quantity} @ ${Replies.scaled(lot.unitCost)} acquired ${lot.acquiredOn} ($holdingDays day(s)$longTermSuffix)"
  }
}
