package st.btu.trading.pillar.models.market

import st.btu.trading.pillar.models.domain.Currency

import java.time.ZonedDateTime

/** `ticker`'s latest observed price, from `source` as of `asOf`. */
final case class Quote(
  ticker: Ticker,
  price: BigDecimal,
  currency: Currency,
  asOf: ZonedDateTime,
  source: Source
)
