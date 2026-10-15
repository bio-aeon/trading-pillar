package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.MarketDataSources
import st.btu.trading.pillar.models.domain.{AccountId, Currency}
import st.btu.trading.pillar.models.market.*
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
    WatchlistItem(ownerId, ticker, enabled = true, None, ZonedDateTime.now)

  private def div(
    ticker: Ticker,
    exDate: LocalDate,
    amount: BigDecimal,
    currency: Option[Currency] = Some(Currency("USD")),
    source: Source = Source.Yahoo
  ): DividendEvent = DividendEvent(ticker, exDate, amount, currency, source)

  // Yahoo is the default source; `settings` set individual tickers to other sources.
  private def sourcesWith(settings: List[TickerSource] = Nil): IO[MarketDataSources[IO]] =
    MarketDataFakes.marketDataSources(settings = settings)

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
          sources <- sourcesWith()
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, sources, accountId)
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
          sources <- sourcesWith()
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, sources, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Trailing 12 months")
          msg must contain("AAPL: 0.50")
          msg must not(contain("99.00"))
        }
      }

      "reads each ticker's dividends from the source it is configured to use" >> {
        val sber = Ticker("SBER")
        val today = LocalDate.now
        for {
          tgGateway <- Fakes.tgGateway()
          watchlist <- MarketDataFakes.watchlistStorage(List(item(aapl), item(sber)))
          dividends <- MarketDataFakes.dividendStorage(
            List(
              // AAPL has no setting of its own, so it uses the default, Yahoo.
              div(aapl, today.plusDays(5), BigDecimal("0.25")),
              div(aapl, today.plusDays(4), BigDecimal("9.99"), source = Source.Tinkoff),
              div(aapl, today.minusMonths(2), BigDecimal("1.00")),
              div(aapl, today.minusMonths(1), BigDecimal("77.00"), source = Source.Tinkoff),
              // SBER is set to Tinkoff.
              div(sber, today.plusDays(6), BigDecimal("30.00"), source = Source.Tinkoff),
              div(sber, today.plusDays(2), BigDecimal("7.77")),
              div(sber, today.minusMonths(2), BigDecimal("2.00"), source = Source.Tinkoff),
              div(sber, today.minusMonths(1), BigDecimal("88.00"))
            )
          )
          sources <- sourcesWith(List(TickerSource(sber, SourceSetting.Tinkoff(None))))
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, sources, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("0.25 USD")
          msg must contain("30.00 USD")
          msg must contain("AAPL: 1.00")
          msg must contain("SBER: 2.00")
          List("9.99", "7.77", "77.00", "88.00").map(other => msg must not(contain(other)))
        }
      }

      "reports empty sections when the watchlist has no dividend history" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          watchlist <- MarketDataFakes.watchlistStorage(List(item(aapl)))
          dividends <- MarketDataFakes.dividendStorage()
          sources <- sourcesWith()
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, sources, accountId)
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
          sources <- sourcesWith()
          handler = new DividendsHandler[IO](tgGateway, dividends, watchlist, sources, accountId)
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
