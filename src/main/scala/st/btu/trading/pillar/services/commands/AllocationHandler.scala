package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.{Allocation, PortfolioValuationService}
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg

// Single-account app for now, same as PositionsHandler/PerformanceHandler: accountId is a
// constructor argument rather than derived from the chat; uses today's date (UTC) as the snapshot
// date the Allocation is computed from.
final class AllocationHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  valuations: PortfolioValuationService[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Allocation

  def usage: String = "/allocation - allocation by instrument type, currency and exchange"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    for {
      today <- Replies.todayUtc[F]
      snapshot <- valuations.positionsAsOf(accountId, today)
      _ <- Replies.reply(tgGateway, chatId, format(Allocation.of(snapshot)))
    } yield ()

  private def format(allocation: Allocation): String = {
    val sections = List(
      "By instrument type:" -> allocation.byInstrumentType,
      "By currency:" -> allocation.byCurrency,
      "By exchange:" -> allocation.byExchange
    ).map { case (title, breakdown) => formatSection(title, breakdown) }
    val cashLine = s"Cash: ${Replies.pct(allocation.cashWeight)}"
    val unpricedLine = Option.when(allocation.unpricedWeightUnknown > 0)(
      s"Unpriced positions (weight unknown): ${allocation.unpricedWeightUnknown}"
    )
    (sections ++ List(Some(cashLine), unpricedLine).flatten).mkString("\n\n")
  }

  // Largest weight first within each breakdown.
  private def formatSection(title: String, breakdown: Map[String, BigDecimal]): String = {
    val lines: Vector[String] =
      if (breakdown.isEmpty) Vector("(none)")
      else
        breakdown.toVector.sortBy { case (_, weight) => -weight }.map { case (key, weight) =>
          s"$key: ${Replies.pct(weight)}"
        }
    (title +: lines).mkString("\n")
  }
}
