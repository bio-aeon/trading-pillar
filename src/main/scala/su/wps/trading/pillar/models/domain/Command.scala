package su.wps.trading.pillar.models.domain

import enumeratum.values._

sealed abstract class Command(val value: String) extends StringEnumEntry

object Command extends StringEnum[Command] {
  case object Ping extends Command("ping")
  case object Info extends Command("info")
  case object Unknown extends Command("unknown")

  val values = findValues
}
