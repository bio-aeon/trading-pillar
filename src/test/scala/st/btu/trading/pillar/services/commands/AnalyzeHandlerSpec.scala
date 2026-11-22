package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.services.analysis.StockAnalysis
import st.btu.trading.pillar.testkit.{AnalysisFakes, Fakes}

import java.time.{ZoneOffset, ZonedDateTime}

class AnalyzeHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val ticker = Ticker("AAPL")
  private val asOf = ZonedDateTime.of(2026, 9, 28, 20, 0, 0, 0, ZoneOffset.UTC)

  private val full = StockAnalysis(
    ticker = ticker,
    price = Some(BigDecimal("150.20")),
    currency = Some(Currency("USD")),
    asOf = Some(asOf),
    instrumentType = Some("EQUITY"),
    exchange = Some("NASDAQ"),
    return1m = Some(BigDecimal("2.50")),
    return3m = Some(BigDecimal("5.10")),
    return1y = Some(BigDecimal("18.40")),
    sma50 = Some(BigDecimal("145.30")),
    sma200 = Some(BigDecimal("130.00")),
    aboveSma50 = Some(true),
    aboveSma200 = Some(true),
    rsi14 = Some(BigDecimal("62.10")),
    atr14Pct = Some(BigDecimal("1.85")),
    high52w = Some(BigDecimal("160.00")),
    low52w = Some(BigDecimal("120.00")),
    distanceToHigh52wPct = Some(BigDecimal("-6.09")),
    distanceToLow52wPct = Some(BigDecimal("25.21")),
    dividends12m = BigDecimal("1.00"),
    dividendYieldPct = Some(BigDecimal("0.67"))
  )

  "AnalyzeHandler" >> {
    "handle" >> {

      "replies with usage when the ticker is missing" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          analysis <- AnalysisFakes.analysisService()
          handler = new AnalyzeHandler[IO](tgGateway, analysis)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("Usage:")
      }

      "upper-cases the ticker and replies with every field, formatted" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          analysis <- AnalysisFakes.analysisService(Map(ticker -> IO.pure(full)))
          handler = new AnalyzeHandler[IO](tgGateway, analysis)
          _ <- handler.handle(chatId, List("aapl"))
          messages <- tgGateway.sentMessages
          calls <- analysis.analyzeCalls
        } yield {
          calls must_== List(ticker)
          val msg = messages.head.message
          msg must contain("AAPL")
          msg must contain("150.20 USD")
          msg must contain("EQUITY")
          msg must contain("NASDAQ")
          msg must contain("SMA50: 145.30")
          msg must contain("Above SMA50: yes")
          msg must contain("RSI14: 62.10")
          msg must contain("ATR14: 1.85%")
          msg must contain("Dist. to 52w high: -6.09%")
          msg must contain("Dividends (12m): 1.00")
          msg must contain("Dividend yield: 0.67%")
          msg must not(contain("n/a"))
        }
      }

      "shows the prices of a coin below a cent in their significant digits" >> {
        val coin = full.copy(
          price = Some(BigDecimal("0.000008230000000000")),
          sma50 = Some(BigDecimal("0.000009100000000000")),
          sma200 = Some(BigDecimal("0.000010200000000000")),
          high52w = Some(BigDecimal("0.000015600000000000")),
          low52w = Some(BigDecimal("0.000003100000000000"))
        )
        for {
          tgGateway <- Fakes.tgGateway()
          analysis <- AnalysisFakes.analysisService(Map(ticker -> IO.pure(coin)))
          handler = new AnalyzeHandler[IO](tgGateway, analysis)
          _ <- handler.handle(chatId, List("aapl"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Price: 0.00000823 USD")
          msg must contain("SMA50: 0.0000091")
          msg must contain("SMA200: 0.0000102")
          msg must contain("52w high: 0.0000156")
          msg must contain("52w low: 0.0000031")
        }
      }

      "shows n/a for every field of an empty analysis (ticker not found)" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          analysis <- AnalysisFakes.analysisService(
            Map(ticker -> IO.pure(StockAnalysis.empty(ticker)))
          )
          handler = new AnalyzeHandler[IO](tgGateway, analysis)
          _ <- handler.handle(chatId, List("AAPL"))
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Price: n/a")
          msg must contain("Dividends (12m): 0.00")
          msg must contain("Dividend yield: n/a")
        }
      }
    }
  }
}
