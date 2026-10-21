package st.btu.trading.pillar.mcp

/** The names clients call the bot's tools by, for text that names a tool, such as the instructions
  * of a server or the guidance of another tool.
  */
object ToolNames {

  /** The name of the read-only tool that reports how the bot serves a ticker. */
  val TickerStatus = "ticker_status"

  /** The name of the read-only tool that fetches a ticker's latest quote from its source. */
  val LiveQuote = "live_quote"

  /** The name of the read-only tool that runs command lines which change nothing. */
  val Query = "bot_query"

  /** The name of the additive tool that runs a backtest and saves the run. */
  val Backtest = "bot_backtest"

  /** The name of the tool that runs any command line, including the ones that change the bot. */
  val Command = "bot_command"
}
