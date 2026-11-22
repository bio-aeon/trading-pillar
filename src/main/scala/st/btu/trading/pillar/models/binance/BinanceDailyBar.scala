package st.btu.trading.pillar.models.binance

import java.time.{Instant, LocalDate}

/** One daily bar of a pair as Binance reports it, with every price and the volume exactly as
  * Binance wrote them.
  *
  * @param date
  *   the UTC day the bar opens on
  * @param volume
  *   the volume traded, in the base asset of the pair
  * @param closeTime
  *   the last millisecond the bar covers: a bar whose close time is still ahead is taking trades
  */
final case class BinanceDailyBar(
  date: LocalDate,
  open: BigDecimal,
  high: BigDecimal,
  low: BigDecimal,
  close: BigDecimal,
  volume: BigDecimal,
  closeTime: Instant
)
