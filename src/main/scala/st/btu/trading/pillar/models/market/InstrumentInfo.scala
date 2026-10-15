package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.Currency

import java.time.ZonedDateTime

/** Descriptive metadata for `ticker`: name, type, currency, exchange and timezone.
  *
  * @param source
  *   the source the metadata was read from; two sources can describe the same ticker differently,
  *   in another currency for instance
  * @param board
  *   the board the metadata was read on, for a source that tells boards apart; `None` otherwise
  */
final case class InstrumentInfo(
  ticker: Ticker,
  source: Source,
  board: Option[ClassCode],
  name: Option[String],
  instrumentType: String,
  currency: Currency,
  exchange: String,
  timezone: String,
  figi: Option[String],
  updatedAt: ZonedDateTime
)
