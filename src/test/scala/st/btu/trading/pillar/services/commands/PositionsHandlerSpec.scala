package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.{PositionSnapshot, PositionValue}
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes}

import java.time.LocalDate

class PositionsHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val today = LocalDate.now

  private def position(
    figi: String,
    ticker: Option[String],
    quantity: Int,
    price: Option[BigDecimal],
    value: Option[BigDecimal],
    weight: Option[BigDecimal]
  ): PositionValue =
    PositionValue(
      Figi(figi),
      ticker.map(Ticker(_)),
      name = None,
      instrumentType = Some("share"),
      currency = Some(rub),
      exchange = Some("MOEX"),
      quantity = quantity,
      price = price,
      value = value,
      weight = weight
    )

  "PositionsHandler" >> {
    "handle" >> {

      "lists positions sorted by value descending, then cash and total" >> {
        val snapshot = PositionSnapshot(
          today,
          rub,
          Vector(
            position(
              "F1",
              Some("AAA"),
              10,
              Some(BigDecimal(50)),
              Some(BigDecimal(500)),
              Some(BigDecimal("0.5"))
            ),
            position(
              "F2",
              Some("BBB"),
              3,
              Some(BigDecimal(100)),
              Some(BigDecimal(300)),
              Some(BigDecimal("0.3"))
            )
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
          handler = new PositionsHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          val aaaIdx = msg.indexOf("AAA")
          val bbbIdx = msg.indexOf("BBB")
          aaaIdx must be_>=(0)
          bbbIdx must be_>(aaaIdx)
          msg must contain("10 @ 50.00 = 500.00 (50.00%)")
          msg must contain("3 @ 100.00 = 300.00 (30.00%)")
          msg must contain("Cash: 200.00")
          msg must contain("Total: 1000.00")
        }
      }

      "marks an unpriced position and falls back to the figi when there is no ticker" >> {
        val snapshot = PositionSnapshot(
          today,
          rub,
          Vector(position("UNMAPPED", None, 5, None, None, None)),
          cash = BigDecimal(0),
          total = BigDecimal(0),
          unpriced = 1
        )
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(accountId -> IO.pure(snapshot))
          )
          handler = new PositionsHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("UNMAPPED 5 (unpriced)")
        }
      }

      "reports 'No positions.' when the snapshot is empty" >> {
        val snapshot = PositionSnapshot(today, rub, Vector.empty, BigDecimal(0), BigDecimal(0), 0)
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationService(positionSnapshots =
            Map(accountId -> IO.pure(snapshot))
          )
          handler = new PositionsHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("No positions.")
      }
    }
  }
}
