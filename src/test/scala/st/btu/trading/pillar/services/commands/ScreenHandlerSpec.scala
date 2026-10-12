package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.screener.{PredefinedScreen, ScreenerRow}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{Fakes, ScreenerFakes}

class ScreenHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)

  private def row(
    symbol: String = "AAPL",
    name: Option[String] = Some("Apple Inc."),
    price: Option[BigDecimal] = Some(BigDecimal("150.5")),
    changePct: Option[BigDecimal] = Some(BigDecimal("2.34"))
  ): ScreenerRow = ScreenerRow(Ticker(symbol), name, price, changePct)

  "ScreenHandler" >> {
    "handle" >> {

      "replies with usage when no screen is given" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener()
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }

      "replies with usage when the screen name isn't recognized" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener()
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, List("bogus"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage")
      }

      "shows ticker, name, price and change % per row, case-insensitively" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener(Map(PredefinedScreen.Gainers -> IO.pure(List(row()))))
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, List("GAINERS"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("AAPL")
          msg must contain("Apple Inc.")
          msg must contain("150.50")
          msg must contain("2.34%")
        }
      }

      "defaults the count to 10" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener(Map(PredefinedScreen.Losers -> IO.pure(List(row()))))
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, List("losers"))
          calls <- screener.predefinedCalls
        } yield calls must_== List((PredefinedScreen.Losers, 10))
      }

      "passes a given count through" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener(Map(PredefinedScreen.Active -> IO.pure(List(row()))))
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, List("active", "5"))
          calls <- screener.predefinedCalls
        } yield calls must_== List((PredefinedScreen.Active, 5))
      }

      "caps the count at 25" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener(Map(PredefinedScreen.Active -> IO.pure(List(row()))))
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, List("active", "100"))
          calls <- screener.predefinedCalls
        } yield calls must_== List((PredefinedScreen.Active, 25))
      }

      "shows ? for a missing name, price or change" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener(
            Map(
              PredefinedScreen.Gainers -> IO
                .pure(List(row(name = None, price = None, changePct = None)))
            )
          )
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, List("gainers"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "AAPL ? ? ?"
      }

      "reports when there are no results" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          screener <- ScreenerFakes.screener(Map(PredefinedScreen.Gainers -> IO.pure(List.empty)))
          handler = new ScreenHandler[IO](tgGateway, screener)
          _ <- handler.handle(chatId, List("gainers"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "No results."
      }
    }
  }
}
