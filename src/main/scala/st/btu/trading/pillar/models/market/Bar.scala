package st.btu.trading.pillar.models.market

import java.time.LocalDate

/** One daily OHLCV bar for `ticker` from `source`. */
final case class Bar(
  ticker: Ticker,
  date: LocalDate,
  open: BigDecimal,
  high: BigDecimal,
  low: BigDecimal,
  close: BigDecimal,
  adjClose: BigDecimal,
  volume: Long,
  source: Source
)
