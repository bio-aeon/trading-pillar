package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.{PositionSnapshot, PositionValue}
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes}

import java.time.LocalDate

class AllocationHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val today = LocalDate.now

  private def position(
    figi: String,
    instrumentType: Option[String],
    currency: Option[String],
    exchange: Option[String],
    weight: Option[BigDecimal]
  ): PositionValue =
    PositionValue(
      Figi(figi),
      ticker = None,
      name = None,
      instrumentType = instrumentType,
      currency = currency.map(Currency(_)),
      exchange = exchange,
      quantity = 1,
      price = None,
      value = None,
      weight = weight
    )

  "AllocationHandler" >> {
    "handle" >> {

      "prints the three breakdowns as percentages, largest first, plus cash" >> {
        val snapshot = PositionSnapshot(
          today,
          rub,
          Vector(
            position("F1", Some("share"), Some("RUB"), Some("MOEX"), Some(BigDecimal("0.3"))),
            position("F2", Some("bond"), Some("USD"), Some("NASDAQ"), Some(BigDecimal("0.5")))
          ),
          cash = BigDecimal(200),
          total = BigDecimal(1000),
          unpriced = 0
        )
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(accountId -> IO.pure(snapshot))
          )
          handler = new AllocationHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("By instrument type:")
          msg must contain("By currency:")
          msg must contain("By exchange:")
          msg must contain("bond: 50.00%")
          msg must contain("share: 30.00%")
          msg.indexOf("bond: 50.00%") must be_<(msg.indexOf("share: 30.00%"))
          msg must contain("Cash: 20.00%")
        }
      }

      "notes unpriced positions when their weight is unknown" >> {
        val snapshot =
          PositionSnapshot(today, rub, Vector.empty, BigDecimal(0), BigDecimal(0), unpriced = 2)
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(accountId -> IO.pure(snapshot))
          )
          handler = new AllocationHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Unpriced positions (weight unknown): 2")
      }

      "prints '(none)' for an empty breakdown and 100% cash weight when everything is cash" >> {
        val snapshot =
          PositionSnapshot(today, rub, Vector.empty, BigDecimal(500), BigDecimal(500), 0)
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(accountId -> IO.pure(snapshot))
          )
          handler = new AllocationHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("(none)")
          msg must contain("Cash: 100.00%")
        }
      }
    }
  }
}
