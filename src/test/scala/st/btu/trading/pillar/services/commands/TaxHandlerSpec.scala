package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.tax.AnnualTaxReport
import st.btu.trading.pillar.testkit.{Fakes, TaxFakes}

import java.time.{ZoneOffset, ZonedDateTime}

class TaxHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)

  private def report(year: Int, unconverted: Int = 0) =
    AnnualTaxReport(year, 600, 0, 600, 50, 20, 5, 78, unconverted, "RUB")

  "TaxHandler" >> {
    "handle" >> {

      "replies with the formatted report for an explicit year" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          taxService <- TaxFakes.taxService(annualReports = Map((accountId, 2024) -> report(2024)))
          handler = new TaxHandler[IO](tgGateway, taxService, accountId)
          _ <- handler.handle(chatId, List("2024"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Tax estimate 2024 (RUB)")
          msg must contain("not tax advice")
          msg must contain("Realized gains: 600.00")
          msg must contain("Long-term exempt: 0.00")
          msg must contain("Taxable gains: 600.00")
          msg must contain("Dividend income: 50.00")
          msg must contain("Coupon income: 20.00")
          msg must contain("Taxes withheld: 5.00")
          msg must contain("Estimated tax: 78.00")
          msg must not(contain("could not be converted"))
        }
      }

      "defaults to the current year when no argument is given" >> {
        val year = ZonedDateTime.now(ZoneOffset.UTC).getYear
        for {
          tgGateway <- Fakes.tgGateway()
          taxService <- TaxFakes.taxService(annualReports = Map((accountId, year) -> report(year)))
          handler = new TaxHandler[IO](tgGateway, taxService, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain(s"Tax estimate $year")
      }

      "appends the unconverted-amounts line when some amounts could not be converted" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          taxService <- TaxFakes.taxService(annualReports =
            Map((accountId, 2024) -> report(2024, unconverted = 3))
          )
          handler = new TaxHandler[IO](tgGateway, taxService, accountId)
          _ <- handler.handle(chatId, List("2024"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain(
          "3 amount(s) could not be converted to RUB and were excluded."
        )
      }
    }
  }
}
