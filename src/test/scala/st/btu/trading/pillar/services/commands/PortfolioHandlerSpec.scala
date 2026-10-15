package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.models.domain.{AccountId, Currency}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes}

import java.time.LocalDate

class PortfolioHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val rub = Currency("RUB")

  "PortfolioHandler" >> {
    "handle" >> {

      "replies with 'No valuation history yet.' when nothing is stored" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          handler = new PortfolioHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No valuation history yet."
      }

      "reports the latest point's date, value, invested, profit and the series length" >> {
        val today = LocalDate.now
        val points = Vector(
          ValuationPoint(today.minusDays(2), 1000, 1000, 1000),
          ValuationPoint(today.minusDays(1), 1100, 1000, 0),
          ValuationPoint(today, 1200, 1050, 50)
        )
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          _ <- valuations.upsert(accountId, rub, points)
          handler = new PortfolioHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain(s"Date: $today")
          msg must contain("Value: 1200.00")
          msg must contain("Invested: 1050.00")
          msg must contain("Profit: 150.00")
          msg must contain("History: 3 day(s)")
        }
      }
    }
  }
}
