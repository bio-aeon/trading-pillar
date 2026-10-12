package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.{AlertKind, AlertRecord}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AlertFakes, Fakes}

import java.time.{ZoneOffset, ZonedDateTime}

class AlertsHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val at = ZonedDateTime.of(2026, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)

  private def delivered(ticker: String): AlertRecord =
    AlertRecord(
      "price:1",
      Some(Ticker(ticker)),
      AlertKind.TargetHit,
      "hash1",
      "msg",
      at,
      None,
      List("tg")
    )

  private def suppressed(ticker: String, reason: String): AlertRecord =
    AlertRecord(
      "price:2",
      Some(Ticker(ticker)),
      AlertKind.PreTarget,
      "hash2",
      "msg",
      at,
      Some(reason),
      Nil
    )

  "AlertsHandler" >> {
    "handle" >> {

      "reports when there is no alert history" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          history <- AlertFakes.alertHistoryStorage()
          handler = new AlertsHandler[IO](tgGateway, history)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No alerts recorded yet."
      }

      "shows time, kind, ticker and delivered or suppressed status" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          history <- AlertFakes.alertHistoryStorage(
            List(delivered("AAPL"), suppressed("MSFT", "cooldown"))
          )
          handler = new AlertsHandler[IO](tgGateway, history)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("2026-01-15 10:30:00")
          msg must contain("AAPL")
          msg must contain("delivered")
          msg must contain("MSFT")
          msg must contain("suppressed (cooldown)")
        }
      }

      "limits the number of records to the given argument" >> {
        val records = (1 to 20).map(i => delivered(s"T$i")).toList
        for {
          tgGateway <- Fakes.tgGateway()
          history <- AlertFakes.alertHistoryStorage(records)
          handler = new AlertsHandler[IO](tgGateway, history)
          _ <- handler.handle(chatId, List("3"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message.split("\n") must have size 3
      }
    }
  }
}
