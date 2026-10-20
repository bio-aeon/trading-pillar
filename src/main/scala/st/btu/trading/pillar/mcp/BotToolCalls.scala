package st.btu.trading.pillar.mcp

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import ch.linkyard.mcp.protocol.Content
import ch.linkyard.mcp.server.ToolFunction
import st.btu.trading.pillar.models.domain.Command
import st.btu.trading.pillar.models.market.{MarketDataError, Quote, Ticker}
import st.btu.trading.pillar.services.commands.{CommandEffect, CommandRunner, ParsedCommand}

import java.util.Locale

// What each bot tool does, in the bot's effect F. A problem the caller is meant to read and correct
// is raised as a ToolFunction.ToolError.
private[mcp] final class BotToolCalls[F[_]: Clock](ports: McpPorts[F])(implicit F: MonadThrow[F]) {

  private val statusReader = new TickerStatusReader[F](ports)

  def tickerStatus(rawTicker: String): F[String] =
    for {
      ticker <- tickerOf(rawTicker)
      status <- statusReader.read(ticker)
    } yield TickerStatus.render(status)

  def liveQuote(rawTicker: String): F[String] =
    tickerOf(rawTicker).flatMap(latestQuote)

  def query(line: String): F[List[Content]] =
    parse(line).flatMap(requireReadOnly).flatMap(run)

  def backtest(line: String): F[List[Content]] =
    parse(line).flatMap(requireBacktest).flatMap(run)

  def command(line: String): F[List[Content]] =
    parse(line).flatMap(run)

  private def toolError[A](message: String): F[A] =
    F.raiseError(ToolFunction.ToolError(List(Content.Text(message))))

  private def tickerOf(raw: String): F[Ticker] = {
    val symbol = raw.trim.toUpperCase(Locale.ROOT)
    if (symbol.isEmpty) toolError("The ticker must not be empty.") else F.pure(Ticker(symbol))
  }

  private def latestQuote(ticker: Ticker): F[String] =
    ports.marketDataSources
      .forTicker(ticker)
      .flatMap(_.latestQuote(ticker))
      .map(_.fold(s"No quote is available for ${ticker.value} right now.")(quoteText))
      .recoverWith { case error: MarketDataError => toolError(errorText(error)) }

  private def quoteText(quote: Quote): String = {
    val price = quote.price.bigDecimal.stripTrailingZeros.toPlainString
    s"${quote.ticker.value}: $price ${quote.currency.value} as of ${quote.asOf.toInstant} " +
      s"(source ${quote.source.value})"
  }

  private def errorText(error: MarketDataError): String =
    error match {
      case MarketDataError.NotFound(ticker) =>
        s"The market data source does not know the ticker ${ticker.value}."
      case MarketDataError.RateLimited(retryAfter) =>
        val advice =
          retryAfter.fold("Try again later.")(delay => s"Try again in ${delay.toSeconds}s.")
        s"The market data source is rate limiting requests. $advice"
      case MarketDataError.SourceNotConfigured(source) =>
        s"No adapter is wired for ${source.value}, the source this ticker is set to use."
      case MarketDataError.Transient(message, _) =>
        s"The market data source failed temporarily: $message"
      case MarketDataError.Other(message, _) => s"The market data request failed: $message"
    }

  private def parse(line: String): F[ParsedCommand] =
    CommandRunner.parse(line).fold(error => toolError(error.message), F.pure)

  private def requireReadOnly(parsed: ParsedCommand): F[ParsedCommand] =
    CommandEffect.of(parsed.command, parsed.args) match {
      case CommandEffect.ReadOnly => F.pure(parsed)
      case CommandEffect.Additive =>
        toolError(
          s"This line saves a backtest run, which ${ToolNames.Query} does not do. " +
            s"Use ${ToolNames.Backtest} for /backtest lines."
        )
      case CommandEffect.Changing =>
        toolError(
          s"This line changes the bot's state, which ${ToolNames.Query} does not do. " +
            s"${ToolNames.Command} runs it, but the operator may not have enabled that tool."
        )
    }

  private def requireBacktest(parsed: ParsedCommand): F[ParsedCommand] =
    if (parsed.command == Command.Backtest) F.pure(parsed)
    else
      toolError(
        s"${ToolNames.Backtest} runs /backtest lines only. " +
          s"Use ${ToolNames.Query} for read-only commands."
      )

  private def run(parsed: ParsedCommand): F[List[Content]] =
    ports.commands.runParsed(parsed).map(CommandOutput.render)
}
