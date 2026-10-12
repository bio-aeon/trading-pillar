package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import st.btu.trading.pillar.alerts.AlertRecord
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.AlertHistoryStorage

import scala.util.Try

final class AlertsHandler[F[_]: Monad](tgGateway: TgGateway[F], history: AlertHistoryStorage[F])
    extends CommandHandler[F] {

  private val DefaultLimit = 10

  def command: Command = Command.Alerts

  def usage: String = "/alerts (n, default 10) - recent alert history"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] = {
    val limit =
      args.headOption.flatMap(s => Try(s.toInt).toOption).filter(_ > 0).getOrElse(DefaultLimit)
    history.recent(limit).flatMap(records => Replies.reply(tgGateway, chatId, format(records)))
  }

  private def format(records: List[AlertRecord]): String =
    if (records.isEmpty) "No alerts recorded yet." else records.map(formatRecord).mkString("\n")

  private def formatRecord(r: AlertRecord): String = {
    val time = r.deliveredAt.format(Replies.TimeFormatter)
    val ticker = r.ticker.map(_.value).getOrElse("-")
    val status = r.suppressedBy.fold("delivered")(reason => s"suppressed ($reason)")
    s"$time ${r.kind.value} $ticker: $status"
  }
}
