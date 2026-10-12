package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{DividendEvent, Source, Ticker, WatchlistItem}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes}

import java.time.{LocalDate, ZonedDateTime}

class DividendsHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)
  private val aapl = Ticker("AAPL")
  private val msft = Ticker("MSFT")

  private def item(ticker: Ticker, ownerId: AccountId = accountId): WatchlistItem =
    WatchlistItem(ownerId, ticker, Source.Yahoo, enabled = true, None, ZonedDateTime.now)

  private def div(
    ticker: Ticker,
    exDate: LocalDate,
    amount: BigDecimal,
    currency: Option[String] = Some("USD")
  ): DividendEvent = DividendEvent(ticker, exDate, amount, currency, Source.Yahoo)

  "DividendsHandler" >> {
    "handle" >> {

      "lists upcoming ex-dates (from today) for the account's watchlist tickers only" >> {
        val today = LocalDate.now
        for {
          tgGateway <- Fakes.tgGateway()
          watchlist <- MarketDataFakes.watchlistStorage(
            List(item(aapl), item(msft, ownerId = otherAccountId))
          )
          dividends <- MarketDataFakes.dividendStorage(
            List(
              div(aapl, today.plusDays(5), BigDecimal("0.25")),
              div(msft, today.plusDays(3), BigDecimal("0.75"))
            )
          )
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("AAPL")
          msg must not(contain("MSFT"))
        }
      }

      "lists trailing 12-month totals per ticker, summing multiple events" >> {
        val today = LocalDate.now
        for {
          tgGateway <- Fakes.tgGateway()
          watchlist <- MarketDataFakes.watchlistStorage(List(item(aapl)))
          dividends <- MarketDataFakes.dividendStorage(
            List(
              div(aapl, today.minusMonths(6), BigDecimal("0.25")),
              div(aapl, today.minusMonths(3), BigDecimal("0.25")),
              div(aapl, today.minusYears(2), BigDecimal("99.00")) // outside the trailing 12 months
            )
          )
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Trailing 12 months")
          msg must contain("AAPL: 0.50")
          msg must not(contain("99.00"))
        }
      }

      "reports empty sections when the watchlist has no dividend history" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          watchlist <- MarketDataFakes.watchlistStorage(List(item(aapl)))
          dividends <- MarketDataFakes.dividendStorage()
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("No upcoming ex-dividend dates.")
          msg must contain("No dividends in the trailing 12 months.")
        }
      }

      "reports empty sections when the account's watchlist is empty" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          watchlist <- MarketDataFakes.watchlistStorage()
          dividends <- MarketDataFakes.dividendStorage(
            List(div(aapl, LocalDate.now, BigDecimal("0.25")))
          )
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("No upcoming ex-dividend dates.")
          msg must contain("No dividends in the trailing 12 months.")
        }
      }
    }
  }
}
