package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.Currency

import java.time.ZonedDateTime

/** Descriptive metadata for `ticker`: name, type, currency, exchange and timezone.
  *
  * @param origin
  *   the setting the metadata was read under, with Tinkoff's board resolved: the source, and the
  *   board or the Yahoo symbol the instrument was looked up by. Two origins can describe one ticker
  *   differently, in another currency for instance.
  */
final case class InstrumentInfo(
  ticker: Ticker,
  origin: SourceSetting,
  name: Option[String],
  instrumentType: String,
  currency: Currency,
  exchange: String,
  timezone: String,
  figi: Option[String],
  updatedAt: ZonedDateTime
)
