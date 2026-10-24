package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.MarketDataSourcesImpl
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes}

private final case class BuiltSourceHandler(
  handler: SourceHandler[IO],
  tgGateway: Fakes.FakeTgGateway[IO],
  settings: MarketDataFakes.FakeTickerSourceStorage
)

class SourceHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val sber = Ticker("SBER")
  private val vod = Ticker("VOD")
  private val cets = ClassCode("CETS")
  private val vodL = YahooSymbol("VOD.L")

  private def build(
    stored: List[TickerSource] = Nil,
    default: SourceSetting = SourceSetting.Yahoo(None)
  ): IO[BuiltSourceHandler] =
    for {
      tgGateway <- Fakes.tgGateway()
      settings <- MarketDataFakes.tickerSourceStorage(stored)
      sources = new MarketDataSourcesImpl[IO](None, None, settings, default)
    } yield BuiltSourceHandler(
      new SourceHandler[IO](tgGateway, settings, sources),
      tgGateway,
      settings
    )

  private def reply(built: BuiltSourceHandler): IO[String] =
    built.tgGateway.sentMessages.map(_.map(_.message).mkString("\n"))

  // Runs `args` against a handler holding `stored` and returns the reply with the settings after.
  private def run(
    args: List[String],
    stored: List[TickerSource] = Nil,
    default: SourceSetting = SourceSetting.Yahoo(None)
  ): IO[(String, List[TickerSource])] =
    for {
      built <- build(stored, default)
      _ <- built.handler.handle(chatId, args)
      text <- reply(built)
      after <- built.settings.all
    } yield (text, after)

  "SourceHandler" >> {
    "handle" >> {

      "listing" >> {

        "lists the tickers that have a source of their own, ordered by ticker, with their board or Yahoo symbol" >> {
          for {
            (text, _) <- run(
              Nil,
              stored = List(
                TickerSource(vod, SourceSetting.Yahoo(Some(vodL))),
                TickerSource(sber, SourceSetting.Tinkoff(Some(ClassCode("TQBR")))),
                TickerSource(Ticker("AAPL"), SourceSetting.Yahoo(None)),
                TickerSource(Ticker("GAZP"), SourceSetting.Tinkoff(None))
              )
            )
          } yield text must_==
            """AAPL - yahoo
              |GAZP - tinkoff, default board
              |SBER - tinkoff, board TQBR
              |VOD - yahoo, symbol VOD.L""".stripMargin
        }

        "says that every ticker uses the default when none has a setting" >> {
          for {
            (text, _) <- run(Nil)
          } yield text must_== "No ticker has a source of its own; every ticker uses the default."
        }
      }

      "showing one ticker" >> {

        "shows the setting stored for the ticker" >> {
          for {
            (text, _) <- run(
              List("sber"),
              stored = List(TickerSource(sber, SourceSetting.Tinkoff(Some(cets))))
            )
          } yield text must_== "SBER uses tinkoff, board CETS."
        }

        "shows the Yahoo symbol of the ticker" >> {
          for {
            (text, _) <- run(
              List("vod"),
              stored = List(TickerSource(vod, SourceSetting.Yahoo(Some(vodL))))
            )
          } yield text must_== "VOD uses yahoo, symbol VOD.L."
        }

        "says when the ticker is on the default source" >> {
          for {
            (text, _) <- run(List("aapl"))
          } yield text must_== "AAPL uses the default: yahoo."
        }

        "shows the board of a default that is a tinkoff setting" >> {
          for {
            (text, _) <- run(List("aapl"), default = SourceSetting.Tinkoff(Some(cets)))
          } yield text must_== "AAPL uses the default: tinkoff, board CETS."
        }
      }

      "setting a source" >> {

        "sets a ticker to yahoo" >> {
          for {
            (text, after) <- run(List("sber", "yahoo"))
          } yield {
            after must_== List(TickerSource(sber, SourceSetting.Yahoo(None)))
            text must_== "SBER now uses yahoo."
          }
        }

        "sets a ticker to yahoo under the given symbol, upper-cased" >> {
          for {
            (text, after) <- run(List("vod", "yahoo", "vod.l"))
          } yield {
            after must_== List(TickerSource(vod, SourceSetting.Yahoo(Some(vodL))))
            text must_== "VOD now uses yahoo, symbol VOD.L."
          }
        }

        "drops the symbol a ticker had when it is set to yahoo without one" >> {
          for {
            (text, after) <- run(
              List("vod", "yahoo"),
              stored = List(TickerSource(vod, SourceSetting.Yahoo(Some(vodL))))
            )
          } yield {
            after must_== List(TickerSource(vod, SourceSetting.Yahoo(None)))
            text must_== "VOD now uses yahoo."
          }
        }

        "sets a ticker to tinkoff on the default board when no board is given" >> {
          for {
            (text, after) <- run(List("sber", "tinkoff"))
          } yield {
            after must_== List(TickerSource(sber, SourceSetting.Tinkoff(None)))
            text must_== "SBER now uses tinkoff, default board."
          }
        }

        "sets a ticker to tinkoff on the given board, upper-cased" >> {
          for {
            (text, after) <- run(List("usd000utstom", "tinkoff", "cets"))
          } yield {
            after must_==
              List(TickerSource(Ticker("USD000UTSTOM"), SourceSetting.Tinkoff(Some(cets))))
            text must_== "USD000UTSTOM now uses tinkoff, board CETS."
          }
        }

        "drops the board a ticker had when it is set to tinkoff without one" >> {
          for {
            (_, after) <- run(
              List("sber", "tinkoff"),
              stored = List(TickerSource(sber, SourceSetting.Tinkoff(Some(cets))))
            )
          } yield after must_== List(TickerSource(sber, SourceSetting.Tinkoff(None)))
        }

        "replaces the setting a ticker had" >> {
          for {
            (_, after) <- run(
              List("sber", "yahoo"),
              stored = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
            )
          } yield after must_== List(TickerSource(sber, SourceSetting.Yahoo(None)))
        }

        "recognises the source word regardless of case" >> {
          for {
            (_, after) <- run(List("sber", "TINKOFF"))
          } yield after must_== List(TickerSource(sber, SourceSetting.Tinkoff(None)))
        }
      }

      "resetting to the default" >> {

        "removes the ticker's setting" >> {
          val other = TickerSource(Ticker("AAPL"), SourceSetting.Yahoo(None))
          for {
            (text, after) <- run(
              List("sber", "default"),
              stored = List(TickerSource(sber, SourceSetting.Tinkoff(None)), other)
            )
          } yield {
            after must_== List(other)
            text must_== "SBER now uses the default source."
          }
        }

        "recognises the default word regardless of case" >> {
          for {
            (_, after) <- run(
              List("sber", "DEFAULT"),
              stored = List(TickerSource(sber, SourceSetting.Yahoo(None)))
            )
          } yield after must beEmpty
        }

        "says when the ticker had no setting to remove" >> {
          for {
            (text, _) <- run(List("sber", "default"))
          } yield text must_== "SBER already uses the default source."
        }
      }

      "malformed input" >> {

        val stored = List(TickerSource(sber, SourceSetting.Yahoo(None)))

        def rejects(args: List[String]) =
          for {
            (text, after) <- run(args, stored)
          } yield {
            text must startWith("Usage: /source")
            after must_== stored
          }

        "rejects a word that names no source" >> {
          rejects(List("sber", "bogus"))
        }

        "rejects more than one symbol for yahoo" >> {
          rejects(List("vod", "yahoo", "VOD.L", "VOD.MX"))
        }

        "rejects more than one board for tinkoff" >> {
          rejects(List("sber", "tinkoff", "TQBR", "CETS"))
        }

        "rejects words after default" >> {
          rejects(List("sber", "default", "now"))
        }
      }
    }
  }
}
