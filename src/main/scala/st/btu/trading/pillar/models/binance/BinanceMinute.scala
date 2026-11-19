package st.btu.trading.pillar.models.binance

import java.time.Instant

/** The one-minute bar of a pair that starts at `start`, the minute whose close values an operation
  * made within it.
  */
final case class BinanceMinute(symbol: BinanceSymbol, start: Instant)

/** The close of a pair's one-minute bar, kept so that a translation made again reads the same
  * price.
  *
  * @param fetchedAt
  *   when the close was read from Binance
  */
final case class BinanceMinutePrice(minute: BinanceMinute, close: BigDecimal, fetchedAt: Instant)
