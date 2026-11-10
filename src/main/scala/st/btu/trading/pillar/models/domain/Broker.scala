package st.btu.trading.pillar.models.domain

import enumeratum.values.{StringEnum, StringEnumEntry}

/** A broker the bot holds accounts at. `value` is the string a consumer persists. */
sealed abstract class Broker(val value: String) extends StringEnumEntry

object Broker extends StringEnum[Broker] {

  /** Tinkoff Invest, whose operations are read through its gRPC API. */
  case object Tinkoff extends Broker("tinkoff")

  /** Interactive Brokers, whose operations are read from the statements of an Activity Flex Query.
    */
  case object Ibkr extends Broker("ibkr")

  val values = findValues
}
