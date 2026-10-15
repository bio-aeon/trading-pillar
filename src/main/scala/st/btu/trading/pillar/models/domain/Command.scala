package st.btu.trading.pillar.models.domain

import enumeratum.values.*

sealed abstract class Command(val value: String) extends StringEnumEntry

object Command extends StringEnum[Command] {
  case object Ping extends Command("ping")
  case object Info extends Command("info")
  case object Watchlist extends Command("watchlist")
  case object Source extends Command("source")
  case object Strategies extends Command("strategies")
  case object Backtest extends Command("backtest")
  case object Sweep extends Command("sweep")
  case object Alerts extends Command("alerts")
  case object Alert extends Command("alert")
  case object Dividends extends Command("dividends")
  case object Analyze extends Command("analyze")
  case object Compare extends Command("compare")
  case object Menu extends Command("menu")
  case object Performance extends Command("performance")
  case object Portfolio extends Command("portfolio")
  case object Positions extends Command("positions")
  case object Allocation extends Command("allocation")
  case object Drift extends Command("drift")
  case object Rebalance extends Command("rebalance")
  case object Paper extends Command("paper")
  case object News extends Command("news")
  case object Screen extends Command("screen")
  case object Tax extends Command("tax")
  case object Lots extends Command("lots")
  case object Export extends Command("export")
  case object Mute extends Command("mute")
  case object Unmute extends Command("unmute")
  case object Help extends Command("help")
  case object Unknown extends Command("unknown")

  val values = findValues
}
