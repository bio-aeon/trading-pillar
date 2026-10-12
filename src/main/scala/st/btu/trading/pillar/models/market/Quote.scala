package st.btu.trading.pillar.models.market

import java.time.ZonedDateTime

/** `ticker`'s latest observed price, from `source` as of `asOf`. */
final case class Quote(
  ticker: Ticker,
  price: BigDecimal,
  currency: String,
  asOf: ZonedDateTime,
  source: Source
)
