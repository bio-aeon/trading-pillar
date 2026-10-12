package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.alerts.StrategyBindingId
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{Quote, Source, Ticker}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.paper.*
import st.btu.trading.pillar.gateways.MarketDataSourcesImpl
import st.btu.trading.pillar.strategy.Side
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes, PaperFakes}

import java.time.{ZoneOffset, ZonedDateTime}

class PaperHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val ticker = Ticker("AAPL")
  private val now = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)

  private def buildHandler(
    account: Option[PaperAccount],
    positions: List[PaperPosition] = List.empty,
    orders: List[PaperOrder] = List.empty,
    quotes: Map[Ticker, IO[Option[Quote]]] = Map.empty
  ): IO[(PaperHandler[IO], Fakes.FakeTgGateway[IO])] =
    for {
      tgGateway <- Fakes.tgGateway()
      paperStorage <- PaperFakes.paperStorage(account.toList, positions, orders)
      marketData <- MarketDataFakes.marketDataSource(quotes = quotes)
      watchlist <- MarketDataFakes.watchlistStorage()
      sources = new MarketDataSourcesImpl[IO](List(marketData), watchlist, marketData.source)
    } yield (new PaperHandler[IO](tgGateway, paperStorage, sources, accountId), tgGateway)

  private def filledOrder(
    id: Long,
    side: Side,
    quantity: Long,
    fillPrice: BigDecimal,
    reason: String
  ): PaperOrder =
    PaperOrder(
      Some(PaperOrderId(id)),
      accountId,
      Some(StrategyBindingId(1)),
      ticker,
      side,
      quantity,
      now,
      filledAt = Some(now),
      fillPrice = Some(fillPrice),
      commission = Some(BigDecimal("0.5")),
      status = PaperOrderStatus.Filled,
      reason = Some(reason)
    )

  private def rejectedOrder(id: Long, side: Side, reason: String): PaperOrder =
    PaperOrder(
      Some(PaperOrderId(id)),
      accountId,
      Some(StrategyBindingId(1)),
      ticker,
      side,
      quantity = 0L,
      requestedAt = now,
      filledAt = None,
      fillPrice = None,
      commission = None,
      status = PaperOrderStatus.Rejected,
      reason = Some(reason)
    )

  "PaperHandler" >> {
    "handle" >> {

      "prints cash, priced positions with P&L, total equity and recent orders" >> {
        val position = PaperPosition(accountId, ticker, 10L, 90, now)
        val orders = List(
          filledOrder(1, Side.Buy, 10L, 90, "ma cross up"),
          rejectedOrder(2, Side.Sell, "Sell without a position (x)")
        )
        for {
          handlerAndGateway <- buildHandler(
            account = Some(PaperAccount(accountId, 1000, "USD")),
            positions = List(position),
            orders = orders,
            quotes = Map(ticker -> IO.pure(Some(Quote(ticker, 100, "USD", now, Source.Yahoo))))
          )
          (handler, tgGateway) = handlerAndGateway
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("Cash: 1000.00")
          msg must contain("AAPL 10 @ 100.00 (avg 90.00) = 1000.00, P&L 100.00")
          msg must contain("Equity: 2000.00")
          msg must contain("filled buy 10 @ 90.00 (ma cross up)")
          msg must contain("rejected sell 0 @ - (Sell without a position (x))")
        }
      }

      "marks a position unpriced when no quote is available, and excludes it from equity" >> {
        val position = PaperPosition(accountId, ticker, 10L, 90, now)
        for {
          handlerAndGateway <- buildHandler(
            account = Some(PaperAccount(accountId, 1000, "USD")),
            positions = List(position),
            quotes = Map.empty
          )
          (handler, tgGateway) = handlerAndGateway
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("AAPL 10 (avg 90.00, unpriced)")
          msg must contain("Equity: 1000.00")
        }
      }

      "reports no positions and no orders when both are empty" >> {
        for {
          handlerAndGateway <- buildHandler(account = Some(PaperAccount(accountId, 500, "USD")))
          (handler, tgGateway) = handlerAndGateway
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("No open positions.")
          msg must contain("No orders yet.")
          msg must contain("Equity: 500.00")
        }
      }

      "reports no paper account configured when the account doesn't exist" >> {
        for {
          handlerAndGateway <- buildHandler(account = None)
          (handler, tgGateway) = handlerAndGateway
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("No paper account configured.")
      }
    }
  }
}
