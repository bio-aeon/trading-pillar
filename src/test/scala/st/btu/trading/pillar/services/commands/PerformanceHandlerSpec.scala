package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes}

import java.time.LocalDate

class PerformanceHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)

  "PerformanceHandler" >> {
    "handle" >> {

      "replies with 'No valuation history yet.' when nothing is stored" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          handler = new PerformanceHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No valuation history yet."
      }

      "reports zeroed metrics and MWR n/a for a single stored point" >> {
        val today = LocalDate.now
        val point = ValuationPoint(today, 1000, 1000, 1000)
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          _ <- valuations.upsert(accountId, "RUB", Vector(point))
          handler = new PerformanceHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain(s"Period: $today to $today")
          msg must contain("Total return: 0.00%")
          msg must contain("TWR: 0.00%")
          msg must contain("MWR: n/a")
          msg must contain("Volatility: 0.00%")
          msg must contain("Sharpe: 0.00")
          msg must contain("Max drawdown: 0.00%")
        }
      }

      "computes total return and TWR across two points, with every metric label present" >> {
        val today = LocalDate.now
        val points = Vector(
          ValuationPoint(today.minusDays(1), 100, 100, 100),
          ValuationPoint(today, 110, 100, 0)
        )
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          _ <- valuations.upsert(accountId, "RUB", points)
          handler = new PerformanceHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Total return: 10.00%")
          msg must contain("TWR: 10.00%")
          msg must contain("MWR:")
          msg must contain("Volatility:")
          msg must contain("Sharpe:")
          msg must contain("Max drawdown:")
        }
      }

      "narrows the query window to the requested period token" >> {
        val today = LocalDate.now
        val outsideOneMonth = ValuationPoint(today.minusMonths(2), 500, 500, 500)
        val insideOneMonth = ValuationPoint(today.minusDays(1), 600, 500, 0)
        for {
          tgGateway <- Fakes.tgGateway()
          valuations <- AnalyticsFakes.portfolioValuationStorage()
          _ <- valuations.upsert(accountId, "RUB", Vector(outsideOneMonth, insideOneMonth))
          handler = new PerformanceHandler[IO](tgGateway, valuations, accountId)
          _ <- handler.handle(chatId, List("1m"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain(s"Period: ${insideOneMonth.date} to ${insideOneMonth.date}")
        }
      }
    }
  }
}
