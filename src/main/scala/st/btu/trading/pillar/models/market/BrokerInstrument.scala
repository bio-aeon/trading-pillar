package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.{Currency, Figi}

import java.time.ZonedDateTime

/** A broker's own record of an instrument, found by its FIGI: the ticker and board it trades under
  * and the descriptive fields the broker reports.
  *
  * @param currency
  *   the currency the instrument settles in
  * @param updatedAt
  *   when this record was fetched from the broker
  */
final case class BrokerInstrument(
  figi: Figi,
  ticker: Ticker,
  classCode: ClassCode,
  name: String,
  instrumentType: String,
  currency: Currency,
  updatedAt: ZonedDateTime
)
