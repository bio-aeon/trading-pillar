package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.effect.Clock
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.analytics.portfolio.{
  Allocation,
  Drift,
  PortfolioValuationService,
  Rebalancer
}
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.AllocationTargetStorage

// Single-account app for now, same as AllocationHandler/PositionsHandler: accountId is a
// constructor argument rather than derived from the chat; uses today's date (UTC) as the snapshot
// date, same convention as /positions and /allocation.
final class DriftHandler[F[_]: Monad: Clock](
  tgGateway: TgGateway[F],
  valuations: PortfolioValuationService[F],
  targets: AllocationTargetStorage[F],
  accountId: AccountId
) extends CommandHandler[F] {

  def command: Command = Command.Drift

  def usage: String = "/drift - allocation targets vs. actual, with drift"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    for {
      today <- Replies.todayUtc[F]
      accountTargets <- targets.byAccount(accountId)
      snapshot <- valuations.positionsAsOf(accountId, today)
      drifts = Rebalancer.drift(Allocation.of(snapshot), snapshot, accountTargets)
      _ <- Replies.reply(tgGateway, chatId, format(drifts))
    } yield ()

  private def format(drifts: List[Drift]): String =
    if (drifts.isEmpty) "No allocation targets configured."
    else drifts.map(formatDrift).mkString("\n")

  private def formatDrift(d: Drift): String = {
    val marker = if (d.breached) " [BREACHED]" else ""
    s"${d.dimension.value}/${d.key}: actual ${pct(d.actualPct)}, target ${pct(d.targetPct)}, " +
      s"drift ${pct(d.driftPct)}$marker"
  }

  // d.actualPct/targetPct/driftPct are already percentage points (Rebalancer.drift's own
  // convention), unlike Replies.pct's 0-1 fraction input - so this formats with Replies.scaled
  // directly instead of going through Replies.pct.
  private def pct(v: BigDecimal): String = s"${Replies.scaled(v)}%"
}
