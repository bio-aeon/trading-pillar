package st.btu.trading.pillar.services.commands

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Command

class CommandParserSpec extends Specification {

  "CommandParser" >> {
    "parse" >> {

      "parses a bare command with no arguments" >> {
        CommandParser.parse("/ping") must beSome(ParsedCommand(Command.Ping, Nil))
      }

      "splits arguments on whitespace" >> {
        CommandParser.parse("/backtest ma-cross-50-200 AAPL 2") must beSome(
          ParsedCommand(Command.Backtest, List("ma-cross-50-200", "AAPL", "2"))
        )
      }

      "collapses repeated whitespace between arguments" >> {
        CommandParser.parse("/watchlist   add   AAPL") must beSome(
          ParsedCommand(Command.Watchlist, List("add", "AAPL"))
        )
      }

      "strips an @BotName suffix from the command with no arguments" >> {
        CommandParser.parse("/ping@MyBot") must beSome(ParsedCommand(Command.Ping, Nil))
      }

      "strips an @BotName suffix and keeps arguments" >> {
        CommandParser.parse("/mute@MyBot 15") must beSome(ParsedCommand(Command.Mute, List("15")))
      }

      "maps an unknown command name to the unknown case, keeping its arguments" >> {
        CommandParser.parse("/frobnicate a b") must beSome(
          ParsedCommand(Command.Unknown, List("a", "b"))
        )
      }

      "returns nothing for text that does not start with a slash" >> {
        CommandParser.parse("hello there") must beNone
      }

      "returns nothing for text with leading whitespace before the slash" >> {
        CommandParser.parse("  /ping") must beNone
      }

      "returns nothing for an empty string" >> {
        CommandParser.parse("") must beNone
      }
    }
  }
}
