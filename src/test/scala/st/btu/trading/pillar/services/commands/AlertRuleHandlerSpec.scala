package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.{PriceCrossed, Proximity}
import st.btu.trading.pillar.alerts.AlertRuleId.value
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AlertFakes, Fakes}

import scala.concurrent.duration._

class AlertRuleHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)
  private val channels = List("telegram")

  "AlertRuleHandler" >> {
    "/alert price" >> {

      "creates a rule, upper-casing the ticker, and replies with its id and a summary" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage()
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("price", "aapl", ">", "300"))
          stored <- rules.all
          messages <- tgGateway.sentMessages
        } yield {
          stored must haveSize(1)
          stored.head.ticker must_== Ticker("AAPL")
          stored.head.accountId must_== accountId
          stored.head.direction must_== PriceCrossed.Direction.Above
          stored.head.threshold must_== BigDecimal(300)
          stored.head.channels must_== channels
          val msg = messages.head.message
          msg must contain(s"Created price rule #${stored.head.id.value}")
          msg must contain("AAPL")
          msg must contain("above")
          msg must contain("300.00")
        }
      }

      "creates a rule with proximity, cooldown and max options" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage()
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(
            chatId,
            List("price", "msft", "<", "150", "proximity=1%", "cooldown=15m", "max=5")
          )
          stored <- rules.all
          messages <- tgGateway.sentMessages
        } yield {
          stored must haveSize(1)
          val rule = stored.head
          rule.direction must_== PriceCrossed.Direction.Below
          rule.proximity must_== Some(Proximity.Percent(BigDecimal(1)))
          rule.policy.cooldown must_== 15.minutes
          rule.policy.maxPerDay must_== Some(5)
          val msg = messages.head.message
          msg must contain("proximity")
          msg must contain("cooldown")
          msg must contain("max 5/day")
        }
      }

      "replies with the one-line usage on a parse error" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage()
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("price", "aapl", "sideways", "300"))
          stored <- rules.all
          messages <- tgGateway.sentMessages
        } yield {
          stored must beEmpty
          messages.head.message must contain("Usage")
        }
      }

      "replies with the one-line usage when the symbol or threshold is missing" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage()
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("price", "aapl"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }
    }

    "/alert rules" >> {

      "reports when the account has no alert rules" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage()
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("rules"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No alert rules."
      }

      "lists only the given account's rules, with ids" >> {
        val mine = AlertFakes.priceCrossed(id = 1L, accountId = accountId, ticker = Ticker("AAPL"))
        val theirs =
          AlertFakes.priceCrossed(id = 2L, accountId = otherAccountId, ticker = Ticker("MSFT"))
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage(List(mine, theirs))
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("rules"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("#1")
          msg must contain("AAPL")
          msg must not(contain("MSFT"))
        }
      }
    }

    "/alert delete" >> {

      "deletes an existing rule owned by the account and confirms" >> {
        val mine = AlertFakes.priceCrossed(id = 1L, accountId = accountId, ticker = Ticker("AAPL"))
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage(List(mine))
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("delete", "1"))
          remaining <- rules.all
          messages <- tgGateway.sentMessages
        } yield {
          remaining must beEmpty
          messages.head.message must contain("Deleted alert rule #1")
        }
      }

      "reports not found for a rule id that does not exist" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage()
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("delete", "99"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("not found")
      }

      "does not delete another account's rule" >> {
        val theirs =
          AlertFakes.priceCrossed(id = 1L, accountId = otherAccountId, ticker = Ticker("MSFT"))
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage(List(theirs))
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("delete", "1"))
          remaining <- rules.all
          messages <- tgGateway.sentMessages
        } yield {
          remaining must haveSize(1)
          messages.head.message must contain("not found")
        }
      }

      "replies with the one-line usage for a non-numeric id" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          rules <- AlertFakes.alertRuleStorage()
          h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
          _ <- h.handle(chatId, List("delete", "abc"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }
    }

    "supports a create, list, delete round trip through the fake storage" >> {
      for {
        tgGateway <- Fakes.tgGateway()
        rules <- AlertFakes.alertRuleStorage()
        h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
        _ <- h.handle(chatId, List("price", "aapl", ">", "300"))
        afterCreate <- rules.all
        createdId = afterCreate.head.id
        _ <- h.handle(chatId, List("rules"))
        _ <- h.handle(chatId, List("delete", createdId.value.toString))
        afterDelete <- rules.all
        messages <- tgGateway.sentMessages
      } yield {
        afterCreate must haveSize(1)
        messages.size must_== 3
        messages(1).message must contain("AAPL")
        messages(2).message must contain(s"Deleted alert rule #${createdId.value}")
        afterDelete must beEmpty
      }
    }

    "replies with the one-line usage for an unrecognized sub-command" >> {
      for {
        tgGateway <- Fakes.tgGateway()
        rules <- AlertFakes.alertRuleStorage()
        h = new AlertRuleHandler[IO](tgGateway, rules, accountId, channels)
        _ <- h.handle(chatId, List("bogus"))
        messages <- tgGateway.sentMessages
      } yield messages.head.message must contain("Usage")
    }
  }
}
