package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.models.market.{ClassCode, Source, SourceSetting, YahooSymbol}

import java.util.Locale

// How the /source and /watchlist handlers read and word a ticker's source setting, so both
// commands, and anything else that reports a setting, name sources the same way.
private[pillar] object SourceSettings {

  // The word that removes a ticker's own setting.
  private val DefaultChoice = "default"

  // What the words after the symbol in `/source SYM ...` ask for.
  sealed trait Choice

  object Choice {

    // Remove the ticker's own setting, so it uses the default again.
    case object Reset extends Choice

    final case class Assign(setting: SourceSetting) extends Choice
  }

  // The setting a command token asks for, case-insensitively: the source's own name, with no board
  // or Yahoo symbol yet. Yahoo and Tinkoff are the only sources a ticker can be set to.
  def parse(token: String): Option[SourceSetting] =
    Source.withValueOpt(token.toLowerCase(Locale.ROOT)).flatMap {
      case Source.Yahoo => Some(SourceSetting.Yahoo(None))
      case Source.Tinkoff => Some(SourceSetting.Tinkoff(None))
      case Source.Binance => None
    }

  // The choice made by the words after the symbol in `/source SYM ...`, or None when they are not a
  // valid one. A board (after tinkoff) or a Yahoo symbol (after yahoo), upper-cased, may follow.
  def parseChoice(words: List[String]): Option[Choice] =
    words match {
      case word :: Nil if word.equalsIgnoreCase(DefaultChoice) => Some(Choice.Reset)
      case word :: rest => parse(word).flatMap(withBoardOrSymbol(_, rest)).map(Choice.Assign(_))
      case Nil => None
    }

  private def withBoardOrSymbol(
    setting: SourceSetting,
    words: List[String]
  ): Option[SourceSetting] =
    words match {
      case Nil => Some(setting)
      case raw :: Nil =>
        val code = raw.toUpperCase(Locale.ROOT)
        setting match {
          case SourceSetting.Yahoo(_) => Some(SourceSetting.Yahoo(Some(YahooSymbol(code))))
          case SourceSetting.Tinkoff(_) => Some(SourceSetting.Tinkoff(Some(ClassCode(code))))
        }
      case _ :: _ :: _ => None
    }

  def describe(setting: SourceSetting): String =
    setting match {
      case SourceSetting.Yahoo(None) => setting.source.value
      case SourceSetting.Yahoo(Some(symbol)) => s"${setting.source.value}, symbol ${symbol.value}"
      case SourceSetting.Tinkoff(None) => s"${setting.source.value}, default board"
      case SourceSetting.Tinkoff(Some(board)) => s"${setting.source.value}, board ${board.value}"
    }
}
