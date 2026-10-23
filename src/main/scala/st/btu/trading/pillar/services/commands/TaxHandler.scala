package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.tax.{AnnualTaxReport, TaxService}

import scala.util.Try

// Single-account app for now, same as PerformanceHandler/DividendsHandler: accountId is a
// constructor argument rather than derived from the chat.
final class TaxHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  taxService: TaxService[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Tax

  def usage: String = "/tax (year, default current year) - estimated annual tax report"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    for {
      year <- resolveYear(args.headOption)
      report <- taxService.annualReport(accountId, year)
      _ <- Replies.reply(tgGateway, chatId, format(report))
    } yield ()

  private def resolveYear(arg: Option[String]): F[Int] =
    arg.flatMap(a => Try(a.toInt).toOption) match {
      case Some(y) => Monad[F].pure(y)
      case None => Replies.todayUtc[F].map(_.getYear)
    }

  private def format(r: AnnualTaxReport): String = {
    val lines = List(
      s"Tax estimate ${r.year} (${r.currency.value}) - not tax advice",
      s"Realized gains: ${Replies.scaled(r.realizedGains)}",
      s"Long-term exempt: ${Replies.scaled(r.exemptGains)}",
      s"Taxable gains: ${Replies.scaled(r.taxableGains)}",
      s"Dividend income: ${Replies.scaled(r.dividendIncome)}",
      s"Coupon income: ${Replies.scaled(r.couponIncome)}",
      s"Taxes withheld: ${Replies.scaled(r.taxesWithheld)}",
      s"Estimated tax: ${Replies.scaled(r.estimatedTax)}"
    )
    val unconvertedLine =
      if (r.unconverted <= 0) Nil
      else
        List(
          s"${r.unconverted} amount(s) could not be converted to ${r.currency.value} and were excluded."
        )
    (lines ++ unconvertedLine).mkString("\n")
  }
}
