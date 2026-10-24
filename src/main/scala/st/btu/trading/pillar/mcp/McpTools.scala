package st.btu.trading.pillar.mcp

import cats.MonadThrow
import cats.effect.Clock
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.Effect

/** The bot's tools for an MCP server, in three groups by what they may do to the bot, so that a
  * consumer serves exactly the groups it chooses:
  *
  *   - [[readOnly]]: `ticker_status`, `live_quote` and `bot_query` only read.
  *   - [[additive]]: `bot_backtest` saves a backtest run.
  *   - [[changing]]: `bot_command` runs any command, including the ones that change the bot.
  *
  * The tools are built by an [[McpToolBuilder]], so they follow its conventions for logging,
  * failures and input schemas. A consumer shares one builder between these tools and its own.
  *
  * Nothing but the consumer's own choice puts [[changing]] in front of a caller: no other member
  * includes it.
  */
final class McpTools[F[_], G[_]] private (calls: BotToolCalls[F], builder: McpToolBuilder[F, G]) {
  import McpTools.*

  /** `ticker_status`, `live_quote` and `bot_query`: tools that read and never change the bot. */
  val readOnly: List[ToolFunction[G]] =
    List(
      builder.text[TickerInput](TickerStatusInfo)(input => calls.tickerStatus(input.ticker)),
      builder.text[TickerInput](LiveQuoteInfo)(input => calls.liveQuote(input.ticker)),
      builder.content[QueryInput](QueryInfo)(input => calls.query(input.command))
    )

  /** `bot_backtest`: the tool that saves a backtest run. Each call adds a run. */
  val additive: List[ToolFunction[G]] =
    List(builder.content[BacktestInput](BacktestInfo)(input => calls.backtest(input.command)))

  /** `bot_command`: the tool that runs any command line, so it can change the watchlist, ticker
    * sources, alert rules and alert muting. Serve it only to callers trusted to do that.
    */
  val changing: List[ToolFunction[G]] =
    List(builder.content[CommandInput](CommandInfo)(input => calls.command(input.command)))
}

object McpTools {

  /** The bot's tools over `ports`, built by `builder`. */
  def create[F[_]: MonadThrow: Clock, G[_]](
    ports: McpPorts[F],
    builder: McpToolBuilder[F, G]
  ): McpTools[F, G] =
    new McpTools[F, G](new BotToolCalls[F](ports), builder)

  private val TickerStatusInfo = ToolFunction.Info(
    name = ToolNames.TickerStatus,
    title = Some("Ticker status"),
    description = Some(
      "Reports how the bot serves a ticker: the market data source it is set to use, stored or " +
        "default, with its board or Yahoo symbol; whether an adapter is wired for that source; " +
        "the account's watchlist entry; the date of the latest stored bar per source; and where " +
        "the stored instrument metadata was read from, with its currency and age. Use it to find " +
        "out why a ticker has no data or looks stale. Reads stored data only."
    ),
    effect = Effect.ReadOnly,
    isOpenWorld = false
  )

  private val LiveQuoteInfo = ToolFunction.Info(
    name = ToolNames.LiveQuote,
    title = Some("Live quote"),
    description = Some(
      "Fetches the latest quote for a ticker from the market data source it is set to use, " +
        "bypassing stored data. Returns the price, currency, time and source, or says why the " +
        "source could not answer."
    ),
    effect = Effect.ReadOnly,
    isOpenWorld = true
  )

  // Open world, like CommandInfo: commands such as /news, /screen and /info call Yahoo and Tinkoff.
  private val QueryInfo = ToolFunction.Info(
    name = ToolNames.Query,
    title = Some("Query the bot"),
    description = Some(
      "Runs a read-only command line against the trading bot, as if typed in its chat, and " +
        "returns the reply: text, CSV files such as /export dividends 2026, and images. " +
        "Examples: /help, /positions, /portfolio, /performance 3m, /watchlist, /source AAPL, " +
        "/alert rules, /strategies. Lines that change the bot or save a backtest are refused " +
        "with the tool to use instead. Start with /help to see every command."
    ),
    effect = Effect.ReadOnly,
    isOpenWorld = true
  )

  private val BacktestInfo = ToolFunction.Info(
    name = ToolNames.Backtest,
    title = Some("Run and save a backtest"),
    description = Some(
      "Runs a /backtest line, for example /backtest ma-cross-50-200 AAPL 2, over the price " +
        "history stored for the ticker and saves the run. Returns the metrics and the equity " +
        "curve image. Each call saves another run, so repeating it is not free of effect."
    ),
    effect = Effect.Additive(idempotent = false),
    isOpenWorld = false
  )

  private val CommandInfo = ToolFunction.Info(
    name = ToolNames.Command,
    title = Some("Run a command that changes the bot"),
    description = Some(
      "Runs any command line against the trading bot, including the ones that change it: " +
        "/watchlist add and rm, /source with a source or default, /alert price and delete, " +
        "/mute and /unmute. A change takes effect at once and some cannot be undone. " +
        s"Use ${ToolNames.Query} to read."
    ),
    effect = Effect.Destructive(idempotent = false),
    isOpenWorld = true
  )
}
