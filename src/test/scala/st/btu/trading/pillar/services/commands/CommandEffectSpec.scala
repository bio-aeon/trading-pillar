package st.btu.trading.pillar.services.commands

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

class CommandEffectSpec extends Specification {

  private def effectOf(line: String): CommandEffect = {
    val parsed = CommandParser.parse(line).get
    CommandEffect.of(parsed.command, parsed.args)
  }

  // Defines an example per line. A group of examples must end in a fragment: the last is returned.
  private def classify(effect: CommandEffect, description: String)(lines: List[String]): Fragment =
    lines.map(line => s"$description: $line" >> { effectOf(line) must_== effect }).last

  "CommandEffect" >> {
    "of" >> {

      "commands that only read" >> {
        classify(CommandEffect.ReadOnly, "reads")(
          List(
            "/ping",
            "/info",
            "/strategies",
            "/sweep ma-cross-50-200 AAPL fast=10:50:10",
            "/alerts 20",
            "/dividends",
            "/analyze AAPL",
            "/compare AAPL MSFT",
            "/menu",
            "/performance 3m",
            "/portfolio",
            "/positions",
            "/allocation",
            "/drift",
            "/rebalance 1000",
            "/paper",
            "/news AAPL 3",
            "/screen gainers",
            "/tax 2025",
            "/lots AAPL",
            "/export operations 2025",
            "/help",
            "/nonsense"
          )
        )
      }

      "watchlist" >> {
        classify(CommandEffect.ReadOnly, "reads")(
          List("/watchlist", "/watchlist add", "/watchlist rm", "/watchlist clear")
        )
        classify(CommandEffect.Changing, "changes")(
          List(
            "/watchlist add AAPL",
            "/watchlist add AAPL tinkoff buy the dip",
            "/watchlist rm AAPL",
            "/watchlist ADD aapl",
            "/watchlist Rm aapl"
          )
        )
      }

      "source" >> {
        classify(CommandEffect.ReadOnly, "reads")(
          List(
            "/source",
            "/source AAPL",
            "/source AAPL fast",
            "/source AAPL yahoo TQBR",
            "/source SBER tinkoff TQBR CETS",
            "/source AAPL default now"
          )
        )
        classify(CommandEffect.Changing, "changes")(
          List(
            "/source AAPL yahoo",
            "/source SBER tinkoff",
            "/source SBER tinkoff cets",
            "/source AAPL default",
            "/source AAPL DEFAULT",
            "/source AAPL Tinkoff"
          )
        )
      }

      "alert" >> {
        classify(CommandEffect.ReadOnly, "reads")(
          List(
            "/alert",
            "/alert rules",
            "/alert price",
            "/alert price AAPL",
            "/alert price AAPL >",
            "/alert price AAPL ~ 300",
            "/alert price AAPL > 300 sometimes=1",
            "/alert delete",
            "/alert delete seven",
            "/alert snooze 7"
          )
        )
        classify(CommandEffect.Changing, "changes")(
          List(
            "/alert price AAPL > 300",
            "/alert price AAPL < 250.5 proximity=1% cooldown=15m max=3",
            "/alert PRICE aapl above 300",
            "/alert delete 7",
            "/alert Delete 7 now"
          )
        )
      }

      "mute and unmute" >> {
        classify(CommandEffect.Changing, "changes")(
          List("/mute", "/mute 30", "/mute soon", "/unmute", "/unmute now")
        )
      }

      "backtest" >> {
        classify(CommandEffect.Additive, "saves a run")(
          List(
            "/backtest ma-cross-50-200 AAPL",
            "/backtest ma-cross-50-200 AAPL 5",
            "/backtest ma-cross-50-200 AAPL,MSFT 2",
            "/backtest ma-cross-50-200 AAPL 2 params=fast:20,slow:100"
          )
        )
        classify(CommandEffect.ReadOnly, "reads")(List("/backtest", "/backtest ma-cross-50-200"))
      }
    }
  }
}
