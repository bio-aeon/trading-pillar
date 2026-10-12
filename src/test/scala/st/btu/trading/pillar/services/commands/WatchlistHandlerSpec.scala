package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{Source, Ticker, WatchlistItem}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes}

import java.time.ZonedDateTime

class WatchlistHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)

  private def item(
    ticker: String,
    note: Option[String] = None,
    enabled: Boolean = true,
    ownerId: AccountId = accountId
  ): WatchlistItem =
    WatchlistItem(ownerId, Ticker(ticker), Source.Yahoo, enabled, note, ZonedDateTime.now)

  "WatchlistHandler" >> {
    "handle" >> {

      "lists only the given account's watchlist items" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage(
            List(item("AAPL"), item("MSFT", ownerId = otherAccountId))
          )
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("AAPL")
          msg must not(contain("MSFT"))
        }
      }

      "includes the note and disabled marker when listing" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage(
            List(item("AAPL", note = Some("core holding"), enabled = false))
          )
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          val msg = messages.head.message
          msg must contain("core holding")
          msg must contain("disabled")
        }
      }

      "reports an empty watchlist" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage()
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield messages.head.message must_== "Watchlist is empty."
      }

      "adds a symbol defaulting to the Yahoo source, upper-cased, with a joined note" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage()
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, List("add", "aapl", "buy", "the", "dip"))
          items <- storage.items
          messages <- tgGateway.sentMessages
        } yield {
          items must haveSize(1)
          items.head.ticker must_== Ticker("AAPL")
          items.head.source must_== Source.Yahoo
          items.head.accountId must_== accountId
          items.head.note must beSome("buy the dip")
          messages.head.message must contain("AAPL")
        }
      }

      "adds a symbol with an explicit tinkoff source token and no note" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage()
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, List("add", "sber", "tinkoff"))
          items <- storage.items
          messages <- tgGateway.sentMessages
        } yield {
          items must haveSize(1)
          items.head.ticker must_== Ticker("SBER")
          items.head.source must_== Source.Tinkoff
          items.head.note must beNone
          messages.head.message must contain("SBER")
          messages.head.message must contain("tinkoff")
        }
      }

      "adds a symbol with an explicit tinkoff source token and a note" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage()
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, List("add", "sber", "tinkoff", "moex", "blue", "chip"))
          items <- storage.items
        } yield {
          items.head.source must_== Source.Tinkoff
          items.head.note must beSome("moex blue chip")
        }
      }

      "adds a symbol with no note" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage()
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, List("add", "AAPL"))
          items <- storage.items
        } yield items.head.note must beNone
      }

      "replies with usage when add is missing a symbol" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage()
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, List("add"))
          items <- storage.items
          messages <- tgGateway.sentMessages
        } yield {
          items must beEmpty
          messages.head.message must contain("Usage")
        }
      }

      "removes an existing symbol and reports it existed" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage(List(item("AAPL")))
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, List("rm", "aapl"))
          messages <- tgGateway.sentMessages
          items <- storage.items
        } yield {
          messages.head.message must contain("Removed AAPL")
          items must beEmpty
        }
      }

      "reports when the symbol to remove was not on the watchlist" >> {
        for {
          tgGateway <- Fakes.tgGateway()
          storage <- MarketDataFakes.watchlistStorage()
          handler = new WatchlistHandler[IO](tgGateway, storage, accountId)
          _ <- handler.handle(chatId, List("rm", "AAPL"))
          messages <- tgGateway.sentMessages
        } yield messages.head.message must contain("not on the watchlist")
      }
    }
  }
}
