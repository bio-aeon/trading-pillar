package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.{Broker, Currency, InstrumentKey}
import st.btu.trading.pillar.models.tcs.FuturesInstrumentType

import java.time.ZonedDateTime

/** A broker's own record of an instrument, found by the key the broker knows it by: the ticker and
  * board it trades under and the descriptive fields the broker reports.
  *
  * @param instrument
  *   the broker and the id that broker gives the instrument
  * @param classCode
  *   the board the instrument trades on, for a broker that names one
  * @param instrumentType
  *   the broker's own name for the kind of instrument, such as `share` or `futures` at Tinkoff
  * @param currency
  *   the currency the instrument settles in
  * @param updatedAt
  *   when this record was fetched from the broker
  */
final case class BrokerInstrument(
  instrument: InstrumentKey,
  ticker: Ticker,
  classCode: Option[ClassCode],
  name: String,
  instrumentType: String,
  currency: Currency,
  updatedAt: ZonedDateTime
) {

  /** Whether the instrument is a futures contract. A position in one counts contracts but is worth
    * none of their price, since the broker settles its gain or loss into cash. Only a Tinkoff
    * record can be one so far.
    */
  def isFuture: Boolean =
    instrument.broker match {
      case Broker.Tinkoff => instrumentType == FuturesInstrumentType
      case Broker.Ibkr | Broker.Binance => false
    }
}
