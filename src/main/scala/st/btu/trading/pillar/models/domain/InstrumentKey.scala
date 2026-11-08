package st.btu.trading.pillar.models.domain

/** An instrument as one broker knows it: the broker, and the id that broker gives the instrument,
  * such as a FIGI at Tinkoff. The same instrument can have a different key at each broker.
  */
final case class InstrumentKey(broker: Broker, id: BrokerInstrumentId)

object InstrumentKey {

  /** Orders keys by the broker's persisted value, then by the instrument id. */
  implicit val ordering: Ordering[InstrumentKey] =
    Ordering.by(key => (key.broker.value, key.id.value))
}
