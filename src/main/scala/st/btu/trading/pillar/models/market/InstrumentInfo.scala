package st.btu.trading.pillar.models.market

import java.time.ZonedDateTime

/** Descriptive metadata for `ticker`: name, type, currency, exchange and timezone. */
final case class InstrumentInfo(
  ticker: Ticker,
  name: Option[String],
  instrumentType: String,
  currency: String,
  exchange: String,
  timezone: String,
  figi: Option[String],
  updatedAt: ZonedDateTime
)
