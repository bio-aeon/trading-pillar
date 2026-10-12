package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.{
  AllocationTarget,
  Dimension,
  PositionSnapshot,
  PositionValue
}
import st.btu.trading.pillar.models.domain.{AccountId, Figi}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes}

import java.time.LocalDate

class RebalanceHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val today = LocalDate.now

  private def position(instrumentType: Option[String], weight: Option[BigDecimal]): PositionValue =
    PositionValue(
      Figi("F1"),
      ticker = None,
      name = None,
      instrumentType = instrumentType,
      currency = None,
      exchange = None,
      quantity = 1,
      price = None,
      value = None,
      weight = weight
    )

  private def handlerAndGateway(snapshot: PositionSnapshot, targets: List[AllocationTarget]) =
    for {
      tgGateway <- Fakes.tgGateway()
      valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
        Map(accountId -> IO.pure(snapshot))
      )
      targetStorage <- AnalyticsFakes.allocationTargetStorage(targets)
    } yield (new RebalanceHandler[IO](tgGateway, valuations, targetStorage, accountId), tgGateway)

  "RebalanceHandler" >> {
    "handle" >> {

      "sells an overweight target down to target, relative to the current total, with no cash argument" >> {
        val snapshot = PositionSnapshot(
          today,
          "RUB",
          Vector(position(Some("share"), Some(BigDecimal("0.6")))),
          cash = BigDecimal(400),
          total = BigDecimal(1000),
          unpriced = 0
        )
        val targets = List(
          AnalyticsFakes.allocationTarget(
            accountId,
            Dimension.InstrumentType,
            "share",
            targetPct = BigDecimal(50)
          )
        )
        for {
          pair <- handlerAndGateway(snapshot, targets)
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield
        // CommandReply.prepare turns '_' into ' ' before the message is sent.
        messages.head.message must contain("Sell instrument type/share: 100.00")
      }

      "parses cash=<amount> and buys the underweight target with the new cash, without selling anything" >> {
        val snapshot =
          PositionSnapshot(
            today,
            "RUB",
            Vector.empty,
            cash = BigDecimal(1000),
            total = BigDecimal(1000),
            unpriced = 0
          )
        val targets = List(
          AnalyticsFakes.allocationTarget(
            accountId,
            Dimension.InstrumentType,
            "share",
            targetPct = BigDecimal(50)
          )
        )
        for {
          pair <- handlerAndGateway(snapshot, targets)
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("cash=1000"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Buy instrument type/share: 1000.00")
          msg must not(contain("Sell"))
        }
      }

      "replies that the portfolio is already within thresholds when there is nothing to suggest" >> {
        val snapshot = PositionSnapshot(
          today,
          "RUB",
          Vector(position(Some("share"), Some(BigDecimal("0.5")))),
          cash = BigDecimal(500),
          total = BigDecimal(1000),
          unpriced = 0
        )
        val targets = List(
          AnalyticsFakes.allocationTarget(
            accountId,
            Dimension.InstrumentType,
            "share",
            targetPct = BigDecimal(50)
          )
        )
        for {
          pair <- handlerAndGateway(snapshot, targets)
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Already within thresholds.")
      }

      "replies with usage on an invalid cash argument" >> {
        val snapshot = PositionSnapshot(today, "RUB", Vector.empty, BigDecimal(0), BigDecimal(0), 0)
        for {
          pair <- handlerAndGateway(snapshot, List.empty)
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("cash=abc"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage:")
      }
    }
  }
}
