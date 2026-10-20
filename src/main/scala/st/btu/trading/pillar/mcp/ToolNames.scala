package st.btu.trading.pillar.mcp

// The names clients call the tools by. The tools' guidance refers to each other by these.
private[mcp] object ToolNames {
  val TickerStatus = "ticker_status"
  val LiveQuote = "live_quote"
  val Query = "bot_query"
  val Backtest = "bot_backtest"
  val Command = "bot_command"
}
