package st.btu.trading.pillar.services.commands

import st.btu.trading.pillar.models.market.{ClassCode, Source, SourceSetting}

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
  // yet. Yahoo and Tinkoff are the only sources a ticker can be set to.
  def parse(token: String): Option[SourceSetting] =
    Source.withValueOpt(token.toLowerCase(Locale.ROOT)).flatMap {
      case Source.Yahoo => Some(SourceSetting.Yahoo)
      case Source.Tinkoff => Some(SourceSetting.Tinkoff(None))
      case Source.Binance => None
    }

  // The choice made by the words after the symbol in `/source SYM ...`, or None when they are not a
  // valid one. A board, upper-cased, may follow tinkoff and nothing else.
  def parseChoice(words: List[String]): Option[Choice] =
    words match {
      case word :: Nil if word.equalsIgnoreCase(DefaultChoice) => Some(Choice.Reset)
      case word :: boardWords => parse(word).flatMap(withBoard(_, boardWords)).map(Choice.Assign(_))
      case Nil => None
    }

  private def withBoard(setting: SourceSetting, boardWords: List[String]): Option[SourceSetting] =
    setting match {
      case SourceSetting.Yahoo => Option.when(boardWords.isEmpty)(setting)
      case SourceSetting.Tinkoff(_) =>
        boardWords match {
          case Nil => Some(setting)
          case boardRaw :: Nil =>
            Some(SourceSetting.Tinkoff(Some(ClassCode(boardRaw.toUpperCase(Locale.ROOT)))))
          case _ :: _ :: _ => None
        }
    }

  def describe(setting: SourceSetting): String =
    setting match {
      case SourceSetting.Yahoo => setting.source.value
      case SourceSetting.Tinkoff(None) => s"${setting.source.value}, default board"
      case SourceSetting.Tinkoff(Some(board)) => s"${setting.source.value}, board ${board.value}"
    }
}
