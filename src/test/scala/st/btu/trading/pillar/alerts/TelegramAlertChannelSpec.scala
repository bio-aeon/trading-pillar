package st.btu.trading.pillar.alerts

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.Fakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.time.{ZoneOffset, ZonedDateTime}

class TelegramAlertChannelSpec extends Specification with CatsEffect {

  private val chatId = ChatId(42L)

  private val alert = Alert(
    "price:1",
    Some(Ticker("AAPL")),
    AlertKind.TargetHit,
    "AAPL target hit",
    "AAPL rose above 100 (now 101)",
    Some(BigDecimal(101)),
    ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC)
  )

  "TelegramAlertChannel" >> {
    "sends a formatted message to the configured chat" >> {
      implicit val logs: Logs[IO, IO] =
        Logs.const[IO, IO](Logging.Make.plain[IO].forService[AlertChannel[IO]])
      for {
        gateway <- Fakes.tgGateway()
        channel <- TelegramAlertChannel.create[IO, IO](gateway, chatId)
        _ <- channel.send(alert)
        sent <- gateway.sentMessages
      } yield {
        sent must haveSize(1)
        sent.head.chatId must_== chatId
        sent.head.message must contain("AAPL target hit")
        sent.head.message must contain("AAPL rose above 100")
      }
    }

    "strips Markdown special characters from the title and body, keeping only the template's own bold markers" >> {
      val messy = alert.copy(title = "AAPL *BUY*", body = "Cross at `100` (see [details])")
      val formatted = TelegramAlertChannel.format(messy)
      // The template itself wraps the title in *bold*, so only the two wrapping stars remain.
      formatted.count(_ == '*') must_== 2
      formatted.contains("`") must beFalse
      formatted.contains("[") must beFalse
      formatted.contains("]") must beFalse
    }

    "turns underscores into spaces rather than dropping them" >> {
      val messy = alert.copy(title = "strategy_buy", body = "fast_sma crossed slow_sma")
      val formatted = TelegramAlertChannel.format(messy)
      formatted must contain("strategy buy")
      formatted must contain("fast sma crossed slow sma")
    }

    "prefixes a system alert with SYSTEM: , and leaves other kinds unprefixed" >> {
      val systemAlert = alert.copy(kind = AlertKind.System, symbol = None, title = "Sync failing")
      TelegramAlertChannel.format(systemAlert) must startWith("SYSTEM: *Sync failing*")
      TelegramAlertChannel.format(alert) must not(startWith("SYSTEM: "))
    }
  }
}
