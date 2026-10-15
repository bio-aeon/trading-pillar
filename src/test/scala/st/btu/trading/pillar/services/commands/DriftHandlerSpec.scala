package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.{Dimension, PositionSnapshot, PositionValue}
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes}

import java.time.LocalDate

class DriftHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
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

  "DriftHandler" >> {
    "handle" >> {

      "prints every enabled target with actual, target and drift, marking breached ones" >> {
        val snapshot = PositionSnapshot(
          today,
          rub,
          Vector(position(Some("share"), Some(BigDecimal("0.6")))),
          cash = BigDecimal(400),
          total = BigDecimal(1000),
          unpriced = 0
        )
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(accountId -> IO.pure(snapshot))
          )
          targets <- AnalyticsFakes.allocationTargetStorage(
            List(
              AnalyticsFakes.allocationTarget(
                accountId,
                Dimension.InstrumentType,
                "share",
                targetPct = BigDecimal(50),
                driftThresholdPct = BigDecimal(5)
              ),
              AnalyticsFakes.allocationTarget(
                accountId,
                Dimension.InstrumentType,
                "bond",
                targetPct = BigDecimal(0),
                driftThresholdPct = BigDecimal(5)
              )
            )
          )
          handler = new DriftHandler[IO](tgGateway, valuations, targets, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          // CommandReply.prepare turns '_' into ' ' and strips '[]' before the message is sent.
          val msg = messages.head.message
          msg must contain(
            "instrument type/share: actual 60.00%, target 50.00%, drift 10.00% BREACHED"
          )
          msg must contain("instrument type/bond: actual 0.00%, target 0.00%, drift 0.00%")
          msg must not(
            contain("instrument type/bond: actual 0.00%, target 0.00%, drift 0.00% BREACHED")
          )
        }
      }

      "reports that there are no targets configured" >> {
        val snapshot = PositionSnapshot(today, rub, Vector.empty, BigDecimal(0), BigDecimal(0), 0)
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(accountId -> IO.pure(snapshot))
          )
          targets <- AnalyticsFakes.allocationTargetStorage()
          handler = new DriftHandler[IO](tgGateway, valuations, targets, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("No allocation targets configured.")
      }
    }
  }
}
