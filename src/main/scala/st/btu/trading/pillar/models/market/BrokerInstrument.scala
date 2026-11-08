package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.{Currency, InstrumentKey}

import java.time.ZonedDateTime

/** A broker's own record of an instrument, found by the key the broker knows it by: the ticker and
  * board it trades under and the descriptive fields the broker reports.
  *
  * @param instrument
  *   the broker and the id that broker gives the instrument
  * @param classCode
  *   the board the instrument trades on, for a broker that names one
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
)
