package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.services.analysis.StockAnalysis
import st.btu.trading.pillar.testkit.{AnalysisFakes, Fakes}

class CompareHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val aapl = Ticker("AAPL")
  private val msft = Ticker("MSFT")

  private def analysisWithPrice(ticker: Ticker, price: BigDecimal): StockAnalysis =
    StockAnalysis.empty(ticker).copy(price = Some(price), currency = Some(Currency("USD")))

  "CompareHandler" >> {
    "handle" >> {

      "replies with usage when a second ticker is missing" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          analysis <- AnalysisFakes.analysisService()
          handler = new CompareHandler[IO](tgGateway, analysis)
          _ <- handler.handle(chatId, List("AAPL"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage:")
      }

      "upper-cases both tickers, analyzes each, and prints the same fields side by side" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          analysis <- AnalysisFakes.analysisService(
            Map(
              aapl -> IO.pure(analysisWithPrice(aapl, BigDecimal("150.20"))),
              msft -> IO.pure(analysisWithPrice(msft, BigDecimal("300.00")))
            )
          )
          handler = new CompareHandler[IO](tgGateway, analysis)
          _ <- handler.handle(chatId, List("aapl", "msft"))
          messages <- tgGateway.sentMessages
          calls <- analysis.analyzeCalls
        } yield {
          calls must_== List(aapl, msft)
          val msg = messages.head.message
          msg must contain("AAPL vs MSFT")
          msg must contain("Price: 150.20 USD | 300.00 USD")
          // Fields neither side has are still shown, n/a on both sides.
          msg must contain("RSI14: n/a | n/a")
        }
      }
    }
  }
}
