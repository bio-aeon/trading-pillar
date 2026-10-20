package st.btu.trading.pillar.mcp

import com.melvinlow.json.schema.JsonSchemaEncoder
import com.melvinlow.json.schema.annotation.JsonSchemaField
import com.melvinlow.json.schema.generic.{auto, semiauto}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax.*

import semiauto.given

// The arguments of the tools. Each field's description is what the model reads in the tool's
// input schema.

private[mcp] final case class TickerInput(
  @JsonSchemaField("description", TickerInput.TickerDescription.asJson)
  ticker: String
)

private[mcp] object TickerInput {
  val TickerDescription: String =
    "The ticker symbol as the bot knows it, for example AAPL or SBER. Case does not matter."

  implicit val schema: JsonSchemaEncoder[TickerInput] = auto.derived
  implicit val decoder: Decoder[TickerInput] = deriveDecoder
}

private[mcp] final case class QueryInput(
  @JsonSchemaField("description", QueryInput.CommandDescription.asJson)
  command: String
)

private[mcp] object QueryInput {
  val CommandDescription: String =
    "A read-only command line starting with /, for example /positions, /watchlist or " +
      "/source AAPL. Send /help to list the commands."

  implicit val schema: JsonSchemaEncoder[QueryInput] = auto.derived
  implicit val decoder: Decoder[QueryInput] = deriveDecoder
}

private[mcp] final case class BacktestInput(
  @JsonSchemaField("description", BacktestInput.CommandDescription.asJson)
  command: String
)

private[mcp] object BacktestInput {
  val CommandDescription: String =
    "A /backtest line: /backtest strategyId SYM[,SYM2,...] (years, default 2) " +
      "(params=key:value,key:value). Several symbols run as one portfolio. " +
      s"${ToolNames.Query} with /strategies lists the strategy ids."

  implicit val schema: JsonSchemaEncoder[BacktestInput] = auto.derived
  implicit val decoder: Decoder[BacktestInput] = deriveDecoder
}

private[mcp] final case class CommandInput(
  @JsonSchemaField("description", CommandInput.CommandDescription.asJson)
  command: String
)

private[mcp] object CommandInput {
  val CommandDescription: String =
    "Any command line starting with /, including the ones that change state, " +
      "for example /watchlist add AAPL or /mute 30."

  implicit val schema: JsonSchemaEncoder[CommandInput] = auto.derived
  implicit val decoder: Decoder[CommandInput] = deriveDecoder
}
