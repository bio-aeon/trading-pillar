package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.MarketDataSourcesImpl
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes}

import java.time.ZonedDateTime

private final case class BuiltWatchlistHandler(
  handler: WatchlistHandler[IO],
  tgGateway: Fakes.FakeTgGateway[IO],
  storage: MarketDataFakes.FakeWatchlistStorage,
  settings: MarketDataFakes.FakeTickerSourceStorage
)

class WatchlistHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)
  private val cets = ClassCode("CETS")
  private val sberOnTinkoff = TickerSource(Ticker("SBER"), SourceSetting.Tinkoff(None))
  private val usdOnCets = TickerSource(Ticker("USD000UTSTOM"), SourceSetting.Tinkoff(Some(cets)))

  private def item(
    ticker: String,
    note: Option[String] = None,
    enabled: Boolean = true,
    ownerId: AccountId = accountId
  ): WatchlistItem =
    WatchlistItem(ownerId, Ticker(ticker), enabled, note, ZonedDateTime.now)

  // A ticker without a stored setting reads as `default`, which is Yahoo unless told otherwise.
  private def build(
    items: List[WatchlistItem] = Nil,
    stored: List[TickerSource] = Nil,
    default: SourceSetting = SourceSetting.Yahoo
  ): IO[BuiltWatchlistHandler] =
    for {
      tgGateway <- Fakes.tgGateway()
      storage <- MarketDataFakes.watchlistStorage(items)
      settings <- MarketDataFakes.tickerSourceStorage(stored)
      sources = new MarketDataSourcesImpl[IO](None, None, settings, default)
    } yield BuiltWatchlistHandler(
      new WatchlistHandler[IO](tgGateway, storage, settings, sources, accountId),
      tgGateway,
      storage,
      settings
    )

  "WatchlistHandler" >> {
    "handle" >> {

      "listing" >> {

        "lists only the given account's watchlist items" >> {
          for {
            built <- build(List(item("AAPL"), item("MSFT", ownerId = otherAccountId)))
            _ <- built.handler.handle(chatId, Nil)
            messages <- built.tgGateway.sentMessages
          } yield {
            val msg = messages.head.message
            msg must contain("AAPL")
            msg must not(contain("MSFT"))
          }
        }

        "shows each item with the source its ticker effectively uses" >> {
          for {
            built <- build(
              items = List(item("AAPL"), item("SBER"), item("USD000UTSTOM"), item("GAZP")),
              stored = List(sberOnTinkoff, usdOnCets)
            )
            _ <- built.handler.handle(chatId, Nil)
            messages <- built.tgGateway.sentMessages
          } yield messages.head.message must_==
            """AAPL (yahoo)
              |SBER (tinkoff, default board)
              |USD000UTSTOM (tinkoff, board CETS)
              |GAZP (yahoo)""".stripMargin
        }

        "includes the note and disabled marker" >> {
          for {
            built <- build(List(item("AAPL", note = Some("core holding"), enabled = false)))
            _ <- built.handler.handle(chatId, Nil)
            messages <- built.tgGateway.sentMessages
          } yield messages.head.message must_== "AAPL (yahoo) - core holding (disabled)"
        }

        "reports an empty watchlist" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, Nil)
            messages <- built.tgGateway.sentMessages
          } yield messages.head.message must_== "Watchlist is empty."
        }
      }

      "add" >> {

        "upper-cases the symbol" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "aapl"))
            items <- built.storage.items
          } yield items.map(_.ticker) must_== List(Ticker("AAPL"))
        }

        "adds the item for the handler's account" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "AAPL"))
            items <- built.storage.items
          } yield items.map(_.accountId) must_== List(accountId)
        }

        "joins the words after the symbol into the note" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "AAPL", "buy", "the", "dip"))
            items <- built.storage.items
          } yield items.head.note must beSome("buy the dip")
        }

        "leaves the ticker's source setting alone when no source token is given" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "AAPL", "buy", "the", "dip"))
            stored <- built.settings.all
          } yield stored must beEmpty
        }

        "replies with the source the ticker effectively uses" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "AAPL"))
            messages <- built.tgGateway.sentMessages
          } yield messages.head.message must_== "Added AAPL (yahoo) to the watchlist."
        }

        "adds a symbol with no note" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "AAPL"))
            items <- built.storage.items
          } yield items.head.note must beNone
        }

        "sets the ticker to tinkoff when the source token says so" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "sber", "tinkoff"))
            items <- built.storage.items
            stored <- built.settings.all
            messages <- built.tgGateway.sentMessages
          } yield {
            items.map(_.ticker) must_== List(Ticker("SBER"))
            items.head.note must beNone
            stored must_== List(sberOnTinkoff)
            messages.head.message must_== "Added SBER (tinkoff, default board) to the watchlist."
          }
        }

        "keeps the words after the source token as the note" >> {
          for {
            built <- build()
            _ <- built.handler.handle(
              chatId,
              List("add", "sber", "tinkoff", "moex", "blue", "chip")
            )
            items <- built.storage.items
          } yield items.head.note must beSome("moex blue chip")
        }

        "sets the ticker to yahoo, replacing a tinkoff setting it had" >> {
          for {
            built <- build(stored =
              List(TickerSource(Ticker("AAPL"), SourceSetting.Tinkoff(Some(cets))))
            )
            _ <- built.handler.handle(chatId, List("add", "aapl", "yahoo"))
            stored <- built.settings.all
          } yield stored must_== List(TickerSource(Ticker("AAPL"), SourceSetting.Yahoo))
        }

        "keeps the board a ticker already has when asked for tinkoff again" >> {
          for {
            built <- build(stored = List(usdOnCets))
            _ <- built.handler.handle(chatId, List("add", "usd000utstom", "tinkoff"))
            stored <- built.settings.all
            messages <- built.tgGateway.sentMessages
          } yield {
            stored must_== List(usdOnCets)
            messages.head.message must contain("tinkoff, board CETS")
          }
        }

        "keeps the board of the router's default when the ticker has no setting of its own" >> {
          for {
            built <- build(default = SourceSetting.Tinkoff(Some(cets)))
            _ <- built.handler.handle(chatId, List("add", "usd000utstom", "tinkoff"))
            stored <- built.settings.all
          } yield stored must_== List(usdOnCets)
        }

        "puts a ticker that was on yahoo on tinkoff's default board" >> {
          for {
            built <- build(stored = List(TickerSource(Ticker("SBER"), SourceSetting.Yahoo)))
            _ <- built.handler.handle(chatId, List("add", "sber", "tinkoff"))
            stored <- built.settings.all
          } yield stored must_== List(sberOnTinkoff)
        }

        "recognises the source token regardless of case" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "sber", "Tinkoff"))
            stored <- built.settings.all
          } yield stored must_== List(sberOnTinkoff)
        }

        "treats a word that names no source as the start of the note" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add", "aapl", "binance", "hedge"))
            items <- built.storage.items
            stored <- built.settings.all
          } yield {
            items.head.note must beSome("binance hedge")
            stored must beEmpty
          }
        }

        "replies with usage when add is missing a symbol" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("add"))
            items <- built.storage.items
            messages <- built.tgGateway.sentMessages
          } yield {
            items must beEmpty
            // The reply sanitizer strips square brackets, so options are marked with parentheses.
            messages.head.message must contain("/watchlist add SYM (yahoo|tinkoff) (note)")
          }
        }
      }

      "rm" >> {

        "removes an existing symbol and reports it existed" >> {
          for {
            built <- build(List(item("AAPL")))
            _ <- built.handler.handle(chatId, List("rm", "aapl"))
            messages <- built.tgGateway.sentMessages
            items <- built.storage.items
          } yield {
            messages.head.message must contain("Removed AAPL")
            items must beEmpty
          }
        }

        "keeps the ticker's source setting when removing it from the watchlist" >> {
          for {
            built <- build(List(item("SBER")), stored = List(sberOnTinkoff))
            _ <- built.handler.handle(chatId, List("rm", "sber"))
            stored <- built.settings.all
          } yield stored must_== List(sberOnTinkoff)
        }

        "reports when the symbol to remove was not on the watchlist" >> {
          for {
            built <- build()
            _ <- built.handler.handle(chatId, List("rm", "AAPL"))
            messages <- built.tgGateway.sentMessages
          } yield messages.head.message must contain("not on the watchlist")
        }
      }
    }
  }
}
