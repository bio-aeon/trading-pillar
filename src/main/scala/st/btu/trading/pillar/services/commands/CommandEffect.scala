package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.models.domain.Command

import scala.util.Try

/** What running a command line does to the bot, from harmless to disruptive. */
sealed trait CommandEffect

object CommandEffect {

  /** Only reads: running the line again changes nothing. */
  case object ReadOnly extends CommandEffect

  /** Adds records and alters none: running the line again adds another. */
  case object Additive extends CommandEffect

  /** Alters or deletes stored state, or changes how the bot behaves. */
  case object Changing extends CommandEffect

  private val WatchlistChangingWords = List("add", "rm")
  private val AlertPriceWord = "price"
  private val AlertDeleteWord = "delete"

  /** The effect of running `command` with `args`, following the grammar of the command's handler.
    *
    * A line the handler would answer with its usage text alone is [[ReadOnly]], however it starts:
    * `/watchlist add` without a symbol, `/source SYM` followed by something that is neither a
    * source nor `default`, `/alert price` with an unreadable rule, `/backtest` with fewer than two
    * arguments. A line that may change something is classified by what it may do, so `/source SYM
    * default` is [[Changing]] even when the ticker already uses the default.
    */
  def of(command: Command, args: List[String]): CommandEffect =
    command match {
      case Command.Watchlist => watchlist(args)
      case Command.Source => source(args)
      case Command.Alert => alert(args)
      case Command.Backtest => backtest(args)
      case Command.Mute | Command.Unmute => Changing
      case Command.Ping | Command.Info | Command.Strategies | Command.Sweep | Command.Alerts |
          Command.Dividends | Command.Analyze | Command.Compare | Command.Menu |
          Command.Performance | Command.Portfolio | Command.Positions | Command.Allocation |
          Command.Drift | Command.Rebalance | Command.Paper | Command.News | Command.Screen |
          Command.Tax | Command.Lots | Command.Export | Command.Help | Command.Unknown =>
        ReadOnly
    }

  private def watchlist(args: List[String]): CommandEffect =
    args match {
      case sub :: _ :: _ if WatchlistChangingWords.exists(sub.equalsIgnoreCase) => Changing
      case _ => ReadOnly
    }

  private def source(args: List[String]): CommandEffect =
    args match {
      case _ :: words if SourceSettings.parseChoice(words).isDefined => Changing
      case _ => ReadOnly
    }

  private def alert(args: List[String]): CommandEffect =
    args match {
      case sub :: _ :: rule if sub.equalsIgnoreCase(AlertPriceWord) =>
        if (PriceRuleParser.parse(rule).isRight) Changing else ReadOnly
      case sub :: id :: _ if sub.equalsIgnoreCase(AlertDeleteWord) =>
        if (Try(id.toLong).isSuccess) Changing else ReadOnly
      case _ => ReadOnly
    }

  private def backtest(args: List[String]): CommandEffect =
    args match {
      case _ :: _ :: _ => Additive
      case _ => ReadOnly
    }
}
