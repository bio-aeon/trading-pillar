package st.btu.trading.pillar.mcp

import cats.arrow.FunctionK
import cats.effect.testing.specs2.CatsEffect
import cats.effect.{Clock, IO}
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.server.ToolFunction
import io.circe.Json
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.*
import st.btu.trading.pillar.models.domain.{AccountId, Command, Currency}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.services.commands.*
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{Fakes, MarketDataFakes, McpFakes}
import tofu.logging.{Logging, Logs}

import java.time.{Instant, LocalDate, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

private final case class McpEnvironment(
  ports: McpPorts[IO],
  watchlist: MarketDataFakes.FakeWatchlistStorage
)

class McpToolsSpec extends Specification with CatsEffect {

  private val now = Instant.parse("2026-10-19T10:00:00Z")
  private implicit val clock: Clock[IO] = McpFakes.clockAt(now)
  private val plainLogs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[McpToolBuilder[IO, IO]])

  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)
  private val aapl = Ticker("AAPL")
  private val sber = Ticker("SBER")
  private val vod = Ticker("VOD")
  private val tqbr = ClassCode("TQBR")
  private val vodL = YahooSymbol("VOD.L")
  private val createdAt = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

  private def answered(text: String): CallTool.Response =
    CallTool.Response.Success(List(Content.Text(text)))

  private def refused(text: String): CallTool.Response =
    CallTool.Response.Error(List(Content.Text(text)))

  private def bar(ticker: Ticker, date: LocalDate, source: Source): Bar =
    Bar(ticker, date, 1, 1, 1, 1, 1, 100, source)

  private def watchlistItem(ticker: Ticker, owner: AccountId, note: Option[String]): WatchlistItem =
    WatchlistItem(owner, ticker, enabled = true, note, createdAt)

  private def metadata(
    ticker: Ticker,
    origin: SourceSetting,
    updatedAt: String,
    currency: String = "RUB"
  ): InstrumentInfo =
    InstrumentInfo(
      ticker,
      origin,
      None,
      "EQUITY",
      Currency(currency),
      "MISX",
      "Europe/Moscow",
      None,
      ZonedDateTime.ofInstant(Instant.parse(updatedAt), ZoneOffset.UTC)
    )

  // Ports over fresh fakes. The command runner is one that must not be used unless given.
  private def environment(
    yahoo: Option[YahooSymbols[IO]] = None,
    tinkoff: Option[TinkoffBoards[IO]] = None,
    stored: List[TickerSource] = Nil,
    watchlist: List[WatchlistItem] = Nil,
    bars: List[Bar] = Nil,
    instruments: Map[Ticker, InstrumentInfo] = Map.empty,
    commands: CommandRunner[IO] = McpFakes.failingCommandRunner(new AssertionError("ran a command"))
  ): IO[McpEnvironment] =
    for {
      settings <- MarketDataFakes.tickerSourceStorage(stored)
      watchlistStorage <- MarketDataFakes.watchlistStorage(watchlist)
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
      instrumentStorage <- MarketDataFakes.instrumentStorage(instruments)
      sources = new MarketDataSourcesImpl[IO](yahoo, tinkoff, settings, SourceSetting.Yahoo(None))
    } yield McpEnvironment(
      McpPorts(
        sources,
        settings,
        watchlistStorage,
        priceHistory,
        instrumentStorage,
        commands,
        accountId
      ),
      watchlistStorage
    )

  // The environment with the command runner of a bot that has the standard handlers and /watchlist,
  // over the same storages as the ports.
  private def environmentWithBot(): IO[McpEnvironment] = {
    implicit val logs: Logs[IO, IO] = plainLogs
    for {
      gateway <- CapturingTgGateway.create[IO, IO]
      tcsFacade <- Fakes.tcsFacade()
      settings <- MarketDataFakes.tickerSourceStorage()
      watchlist <- MarketDataFakes.watchlistStorage()
      priceHistory <- MarketDataFakes.priceHistoryStorage()
      instruments <- MarketDataFakes.instrumentStorage()
      sources = new MarketDataSourcesImpl[IO](None, None, settings, SourceSetting.Yahoo(None))
      handlers = CommandHandlers.standard[IO](
        gateway,
        tcsFacade,
        List(new WatchlistHandler[IO](gateway, watchlist, settings, sources, accountId))
      )
      runner <- CommandRunnerImpl.create[IO, IO](gateway, handlers)
    } yield McpEnvironment(
      McpPorts(sources, settings, watchlist, priceHistory, instruments, runner, accountId),
      watchlist
    )
  }

  private def toolsOver(environment: McpEnvironment): IO[McpTools[IO, IO]] = {
    implicit val builderLogs: Logs[IO, IO] = plainLogs
    McpToolBuilder.create[IO, IO, IO](FunctionK.id[IO]).map(McpTools.create(environment.ports, _))
  }

  private def named(tools: List[ToolFunction[IO]], name: String): ToolFunction[IO] =
    tools.find(_.name == name).getOrElse(throw new NoSuchElementException(name))

  private def tickerStatus(tools: McpTools[IO, IO]) = named(tools.readOnly, "ticker_status")
  private def liveQuote(tools: McpTools[IO, IO]) = named(tools.readOnly, "live_quote")
  private def botQuery(tools: McpTools[IO, IO]) = named(tools.readOnly, "bot_query")
  private def botBacktest(tools: McpTools[IO, IO]) = named(tools.additive, "bot_backtest")
  private def botCommand(tools: McpTools[IO, IO]) = named(tools.changing, "bot_command")

  "McpTools" >> {
    "groups" >> {

      "offer the read-only tools in a fixed order" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield tools.readOnly.map(_.name) must_== List("ticker_status", "live_quote", "bot_query")
      }

      "offer the backtest tool apart from the read-only ones" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield tools.additive.map(_.name) must_== List("bot_backtest")
      }

      "offer the tool that changes the bot apart from every other" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield tools.changing.map(_.name) must_== List("bot_command")
      }

      "declare the read-only tools read-only" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield tools.readOnly.map(_.info.effect).distinct must_== List(
          ToolFunction.Effect.ReadOnly
        )
      }

      "declare as open to the outside world the tools that can reach a market data source" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield (tools.readOnly ++ tools.additive ++ tools.changing)
          .map(tool => tool.name -> tool.info.isOpenWorld) must_== List(
          "ticker_status" -> false,
          "live_quote" -> true,
          "bot_query" -> true,
          "bot_backtest" -> false,
          "bot_command" -> true
        )
      }

      "declare the backtest tool as adding, and not repeatable for free" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield tools.additive.map(_.info.effect) must_== List(
          ToolFunction.Effect.Additive(idempotent = false)
        )
      }

      "declare the tool that changes the bot as destructive, and not repeatable for free" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield tools.changing.map(_.info.effect) must_== List(
          ToolFunction.Effect.Destructive(idempotent = false)
        )
      }

      "describe every argument in the tool's input schema" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield {
          val everyTool = tools.readOnly ++ tools.additive ++ tools.changing
          val described = everyTool.map { tool =>
            val field = if (tool.name.startsWith("bot_")) "command" else "ticker"
            Json
              .fromJsonObject(tool.argsSchema)
              .hcursor
              .downField("properties")
              .downField(field)
              .get[String]("description")
              .map(_.nonEmpty)
          }
          described must_== List.fill(everyTool.size)(Right(true))
        }
      }

      "require the one argument of every tool" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
        } yield (tools.readOnly ++ tools.additive ++ tools.changing).map { tool =>
          tool.name -> Json.fromJsonObject(tool.argsSchema).hcursor.get[List[String]]("required")
        } must_== List(
          "ticker_status" -> Right(List("ticker")),
          "live_quote" -> Right(List("ticker")),
          "bot_query" -> Right(List("command")),
          "bot_backtest" -> Right(List("command")),
          "bot_command" -> Right(List("command"))
        )
      }
    }

    "ticker_status" >> {

      "reports a Tinkoff board setting with no adapter, bars and metadata of another origin" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource()
          env <- environment(
            yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)),
            stored = List(TickerSource(sber, SourceSetting.Tinkoff(Some(tqbr)))),
            watchlist = List(watchlistItem(sber, accountId, Some("core holding"))),
            bars = List(bar(sber, LocalDate.of(2026, 3, 2), Source.Yahoo)),
            instruments =
              Map(sber -> metadata(sber, SourceSetting.Yahoo(None), "2026-10-16T10:00:00Z"))
          )
          tools <- toolsOver(env)
          response <- McpFakes.call(tickerStatus(tools), "ticker" -> "sber")
        } yield response must_== answered(
          List(
            "SBER",
            "Source setting: tinkoff, board TQBR (set for this ticker)",
            "Adapter: none wired for tinkoff, so the sync skips this ticker",
            "Watchlist: enabled, note \"core holding\"",
            "Stored bars, latest date: yahoo 2026-03-02, tinkoff none",
            "Metadata: read from yahoo, currency RUB, updated 3 days ago",
            "Metadata origin: differs from the source setting; " +
              "once an adapter is wired, the sync re-reads the metadata and refetches the bars"
          ).mkString("\n")
        )
      }

      "reports the Yahoo default of a ticker with no data" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource()
          env <- environment(yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)))
          tools <- toolsOver(env)
          response <- McpFakes.call(tickerStatus(tools), "ticker" -> "AAPL")
        } yield response must_== answered("""AAPL
            |Source setting: yahoo (none set for this ticker, so the default)
            |Adapter: wired (yahoo)
            |Watchlist: not on the watchlist
            |Stored bars, latest date: yahoo none, tinkoff none
            |Metadata: none stored""".stripMargin)
      }

      "reports the adapter's board and matching metadata of a Tinkoff ticker" >> {
        for {
          tinkoffSource <- MarketDataFakes.marketDataSource(SourceSetting.Tinkoff(Some(tqbr)))
          env <- environment(
            tinkoff = Some(MarketDataFakes.tinkoffBoardsOver(tinkoffSource)),
            stored = List(TickerSource(sber, SourceSetting.Tinkoff(None))),
            bars = List(bar(sber, LocalDate.of(2026, 10, 16), Source.Tinkoff)),
            instruments =
              Map(sber -> metadata(sber, SourceSetting.Tinkoff(Some(tqbr)), "2026-10-19T09:00:00Z"))
          )
          tools <- toolsOver(env)
          response <- McpFakes.call(tickerStatus(tools), "ticker" -> "SBER")
        } yield response must_== answered("""SBER
            |Source setting: tinkoff, default board (set for this ticker)
            |Adapter: wired (tinkoff, board TQBR)
            |Watchlist: not on the watchlist
            |Stored bars, latest date: yahoo none, tinkoff 2026-10-16
            |Metadata: read from tinkoff, board TQBR, currency RUB, updated 1 hour ago
            |Metadata origin: matches the source setting""".stripMargin)
      }

      "reports the Yahoo symbol of an aliased ticker in its setting, adapter and metadata" >> {
        for {
          env <- environment(
            yahoo = Some(MarketDataFakes.symbolViews),
            stored = List(TickerSource(vod, SourceSetting.Yahoo(Some(vodL)))),
            bars = List(bar(vod, LocalDate.of(2026, 10, 16), Source.Yahoo)),
            instruments = Map(
              vod -> metadata(
                vod,
                SourceSetting.Yahoo(Some(vodL)),
                "2026-10-19T09:00:00Z",
                currency = "GBP"
              )
            )
          )
          tools <- toolsOver(env)
          response <- McpFakes.call(tickerStatus(tools), "ticker" -> "vod")
        } yield response must_== answered("""VOD
            |Source setting: yahoo, symbol VOD.L (set for this ticker)
            |Adapter: wired (yahoo, symbol VOD.L)
            |Watchlist: not on the watchlist
            |Stored bars, latest date: yahoo 2026-10-16, tinkoff none
            |Metadata: read from yahoo, symbol VOD.L, currency GBP, updated 1 hour ago
            |Metadata origin: matches the source setting""".stripMargin)
      }

      "shows only the watchlist entry of the configured account" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource()
          env <- environment(
            yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)),
            watchlist = List(watchlistItem(aapl, otherAccountId, Some("not mine")))
          )
          tools <- toolsOver(env)
          response <- McpFakes.call(tickerStatus(tools), "ticker" -> "AAPL")
        } yield response must beLike {
          case CallTool.Response.Success(List(Content.Text(report, _, _)), _, _) =>
            report must contain("Watchlist: not on the watchlist")
        }
      }

      "refuses an empty ticker" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
          response <- McpFakes.call(tickerStatus(tools), "ticker" -> "  ")
        } yield response must_== refused("The ticker must not be empty.")
      }
    }

    "live_quote" >> {
      val quote = Quote(
        aapl,
        BigDecimal("189.30"),
        Currency("USD"),
        ZonedDateTime.of(2026, 10, 19, 10, 15, 0, 0, ZoneOffset.UTC),
        Source.Yahoo
      )

      def callWith(result: IO[Option[Quote]], ticker: String = "AAPL"): IO[CallTool.Response] =
        for {
          yahoo <- MarketDataFakes.marketDataSource(quotes = Map(aapl -> result))
          env <- environment(yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)))
          tools <- toolsOver(env)
          response <- McpFakes.call(liveQuote(tools), "ticker" -> ticker)
        } yield response

      "returns the quote of the ticker's adapter" >> {
        callWith(IO.pure(Some(quote)))
          .map(_ must_== answered("AAPL: 189.3 USD as of 2026-10-19T10:15:00Z (source yahoo)"))
      }

      "ignores case and surrounding whitespace in the ticker" >> {
        callWith(IO.pure(Some(quote)), " aapl ")
          .map(_ must_== answered("AAPL: 189.3 USD as of 2026-10-19T10:15:00Z (source yahoo)"))
      }

      "says so when the source has no quote right now" >> {
        callWith(IO.pure(None)).map(_ must_== answered("No quote is available for AAPL right now."))
      }

      "reports a source with no adapter wired" >> {
        for {
          yahoo <- MarketDataFakes.marketDataSource()
          env <- environment(
            yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)),
            stored = List(TickerSource(aapl, SourceSetting.Tinkoff(None)))
          )
          tools <- toolsOver(env)
          response <- McpFakes.call(liveQuote(tools), "ticker" -> "AAPL")
        } yield response must_== refused(
          "No adapter is wired for tinkoff, the source this ticker is set to use."
        )
      }

      "market data failures" >> {
        List[(String, MarketDataError, String)](
          (
            "an unknown ticker",
            MarketDataError.NotFound(aapl),
            "The market data source does not know the ticker AAPL."
          ),
          (
            "a rate limit with a retry delay",
            MarketDataError.RateLimited(Some(30.seconds)),
            "The market data source is rate limiting requests. Try again in 30s."
          ),
          (
            "a rate limit without a retry delay",
            MarketDataError.RateLimited(None),
            "The market data source is rate limiting requests. Try again later."
          ),
          (
            "a temporary failure",
            MarketDataError.Transient("connection reset", None),
            "The market data source failed temporarily: connection reset"
          ),
          (
            "any other failure",
            MarketDataError.Other("unreadable response", None),
            "The market data request failed: unreadable response"
          )
        ).map { case (description, error, words) =>
          s"explains $description in plain words" >> {
            callWith(IO.raiseError(error)).map(_ must_== refused(words))
          }
        }.last
      }
    }

    "bot_query" >> {

      "runs a read-only command line and returns the reply" >> {
        for {
          env <- environmentWithBot()
          tools <- toolsOver(env)
          response <- McpFakes.call(botQuery(tools), "command" -> "/help")
        } yield response must_== answered("""/ping - health check
            |/info - portfolio positions
            |/watchlist, /watchlist add SYM (yahoo|tinkoff) (note), or /watchlist rm SYM
            |/help - list available commands""".stripMargin)
      }

      "runs the read form of a command that can also change the bot" >> {
        for {
          env <- environmentWithBot()
          _ <- env.watchlist.add(watchlistItem(aapl, accountId, None))
          tools <- toolsOver(env)
          response <- McpFakes.call(botQuery(tools), "command" -> "/watchlist")
        } yield response must_== answered("AAPL (yahoo)")
      }

      "refuses a line that changes the bot and points to the tool that does" >> {
        for {
          env <- environmentWithBot()
          tools <- toolsOver(env)
          response <- McpFakes.call(botQuery(tools), "command" -> "/watchlist add AAPL")
          watched <- env.watchlist.items
        } yield (response must_== refused(
          "This line changes the bot's state, which bot_query does not do. " +
            "bot_command runs it, but the operator may not have enabled that tool."
        )) and (watched must beEmpty)
      }

      "refuses a backtest and points to the tool that runs it" >> {
        for {
          runner <- McpFakes.commandRunner(Nil)
          env <- environment(commands = runner)
          tools <- toolsOver(env)
          response <- McpFakes.call(
            botQuery(tools),
            "command" -> "/backtest ma-cross-50-200 AAPL 2"
          )
          ran <- runner.lines
        } yield (response must_== refused(
          "This line saves a backtest run, which bot_query does not do. " +
            "Use bot_backtest for /backtest lines."
        )) and (ran must beEmpty)
      }

      "refuses a line that is not a command" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
          response <- McpFakes.call(botQuery(tools), "command" -> "show me the positions")
        } yield response must_== refused(
          "Not a command: a command line starts with '/', for example /help"
        )
      }
    }

    "bot_backtest" >> {

      "runs a backtest line and returns its text and image" >> {
        for {
          runner <- McpFakes.commandRunner(
            List(
              CapturedReply.Text("Backtest #1: ma-cross-50-200 on AAPL"),
              CapturedReply.Photo(Array[Byte](1, 2, 3), None)
            )
          )
          env <- environment(commands = runner)
          tools <- toolsOver(env)
          response <- McpFakes.call(
            botBacktest(tools),
            "command" -> "/backtest ma-cross-50-200 AAPL 2"
          )
          ran <- runner.lines
        } yield (response must beLike {
          case CallTool.Response.Success(
                List(Content.Text(text, _, _), Content.Image(data, mimeType, _, _)),
                _,
                _
              ) =>
            (text must_== "Backtest #1: ma-cross-50-200 on AAPL") and
              (mimeType must_== "image/png") and (data.toList must_== List[Byte](1, 2, 3))
        }) and (ran must_== List(
          ParsedCommand(Command.Backtest, List("ma-cross-50-200", "AAPL", "2"))
        ))
      }

      "refuses a line that is not a backtest and points to the read-only tool" >> {
        for {
          runner <- McpFakes.commandRunner(Nil)
          env <- environment(commands = runner)
          tools <- toolsOver(env)
          response <- McpFakes.call(botBacktest(tools), "command" -> "/positions")
          ran <- runner.lines
        } yield (response must_== refused(
          "bot_backtest runs /backtest lines only. Use bot_query for read-only commands."
        )) and (ran must beEmpty)
      }
    }

    "bot_command" >> {

      "runs a command that changes the bot" >> {
        for {
          env <- environmentWithBot()
          tools <- toolsOver(env)
          response <- McpFakes.call(botCommand(tools), "command" -> "/watchlist add aapl")
          watched <- env.watchlist.items
        } yield (response must_== answered("Added AAPL (yahoo) to the watchlist.")) and
          (watched.map(_.ticker) must_== List(aapl))
      }

      "runs a read-only command too" >> {
        for {
          env <- environmentWithBot()
          tools <- toolsOver(env)
          response <- McpFakes.call(botCommand(tools), "command" -> "/watchlist")
        } yield response must_== answered("Watchlist is empty.")
      }

      "refuses a line that is not a command" >> {
        for {
          env <- environment()
          tools <- toolsOver(env)
          response <- McpFakes.call(botCommand(tools), "command" -> "mute everything")
        } yield response must_== refused(
          "Not a command: a command line starts with '/', for example /help"
        )
      }
    }
  }
}
