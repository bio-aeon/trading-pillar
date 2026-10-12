package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.models.domain.Command

/** A command name with its raw, whitespace-split arguments. */
final case class ParsedCommand(command: Command, args: List[String])

object CommandParser {

  /** Whether `text` looks like a command at all, i.e. starts with `"/"`. */
  def isCommand(text: String): Boolean = text.startsWith("/")

  /** Parses `"/cmd"`, `"/cmd@BotName"`, or either form followed by whitespace-separated arguments.
    *
    * @return
    *   `None` if `text` does not start with `"/"`; a command name that isn't a known `Command`
    *   value parses to `Command.Unknown` with its arguments, rather than failing
    */
  def parse(text: String): Option[ParsedCommand] =
    if (!isCommand(text)) None
    else
      text.trim.split("\\s+").toList match {
        case token :: args =>
          val name = token.stripPrefix("/").takeWhile(_ != '@')
          Some(ParsedCommand(Command.withValueOpt(name).getOrElse(Command.Unknown), args))
        case Nil => None
      }
}
