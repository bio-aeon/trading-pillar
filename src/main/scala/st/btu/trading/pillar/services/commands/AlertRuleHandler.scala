package st.btu.trading.pillar.services.commands

import cats.Monad
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.alerts.*
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.{AccountId, Command}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg
import st.btu.trading.pillar.storages.AlertRuleStorage

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

// Single-account app for now, same as WatchlistHandler/DividendsHandler: accountId is a
// constructor argument rather than derived from the chat. defaultChannels is the channel list
// every rule created through this handler gets -- there is no per-rule channel syntax yet.
final class AlertRuleHandler[F[_]: Monad](
  tgGateway: TgGateway[F],
  rules: AlertRuleStorage[F],
  accountId: AccountId,
  defaultChannels: List[String]
) extends CommandHandler[F] {

  def command: Command = Command.Alert

  def usage: String =
    "/alert price SYM >|< threshold [proximity=1%|2.5|0.5atr14] [cooldown=15m] [max=N], " +
      "/alert rules, or /alert delete id"

  def handle(chatId: tg.ChatId, args: List[String]): F[Unit] =
    args match {
      case sub :: rest if sub.equalsIgnoreCase("price") => price(chatId, rest)
      case sub :: _ if sub.equalsIgnoreCase("rules") => list(chatId)
      case sub :: rest if sub.equalsIgnoreCase("delete") => delete(chatId, rest)
      case _ => usageReply(chatId)
    }

  private def price(chatId: tg.ChatId, rest: List[String]): F[Unit] =
    rest match {
      case symRaw :: tail =>
        PriceRuleParser.parse(tail) match {
          case Right(parsed) => create(chatId, Ticker(symRaw.toUpperCase), parsed)
          case Left(_) => usageReply(chatId)
        }
      case Nil => usageReply(chatId)
    }

  private def create(chatId: tg.ChatId, ticker: Ticker, parsed: ParsedPriceRule): F[Unit] = {
    val newRule = NewPriceRule(
      accountId,
      ticker,
      parsed.threshold,
      parsed.direction,
      parsed.proximity,
      defaultChannels,
      parsed.cooldown,
      parsed.maxPerDay
    )
    val cooldown = parsed.cooldown.getOrElse(DeliveryPolicy.DefaultCooldown)
    rules.insertPriceRule(newRule).flatMap { id =>
      val summary =
        summarize(
          ticker,
          parsed.direction,
          parsed.threshold,
          parsed.proximity,
          cooldown,
          parsed.maxPerDay
        )
      Replies.reply(tgGateway, chatId, s"Created price rule #${id.value}: $summary")
    }
  }

  private def list(chatId: tg.ChatId): F[Unit] =
    rules.byAccount(accountId).flatMap { rs =>
      val text = if (rs.isEmpty) "No alert rules." else rs.map(formatRule).mkString("\n")
      Replies.reply(tgGateway, chatId, text)
    }

  private def formatRule(r: PriceCrossed): String = {
    val summary =
      summarize(
        r.ticker,
        r.direction,
        r.threshold,
        r.proximity,
        r.policy.cooldown,
        r.policy.maxPerDay
      )
    s"#${r.id.value} $summary"
  }

  private def delete(chatId: tg.ChatId, rest: List[String]): F[Unit] =
    rest match {
      case idRaw :: _ =>
        Try(idRaw.toLong).toOption match {
          case Some(idValue) =>
            rules.delete(accountId, AlertRuleId(idValue)).flatMap { existed =>
              val text =
                if (existed) s"Deleted alert rule #$idValue."
                else s"Alert rule #$idValue not found."
              Replies.reply(tgGateway, chatId, text)
            }
          case None => usageReply(chatId)
        }
      case Nil => usageReply(chatId)
    }

  private def summarize(
    ticker: Ticker,
    direction: PriceCrossed.Direction,
    threshold: BigDecimal,
    proximity: Option[Proximity],
    cooldown: FiniteDuration,
    maxPerDay: Option[Int]
  ): String = {
    val directionWord = direction.name
    val parts = List(
      proximity.map(p => s"proximity ${describeProximity(p)}"),
      Some(s"cooldown $cooldown"),
      maxPerDay.map(m => s"max $m/day")
    ).flatten
    s"${ticker.value} $directionWord ${Replies.scaled(threshold)} (${parts.mkString(", ")})"
  }

  private def describeProximity(p: Proximity): String = p match {
    case Proximity.Percent(pct) => s"${Replies.scaled(pct)}%"
    case Proximity.Absolute(x) => Replies.scaled(x)
    case Proximity.Atr(mult, period) => s"${Replies.scaled(mult)}xATR($period)"
  }

  private def usageReply(chatId: tg.ChatId): F[Unit] =
    Replies.reply(tgGateway, chatId, s"Usage: $usage")
}
