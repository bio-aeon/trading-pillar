package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.binance.{BinanceMinute, BinanceMinutePrice}

/** The closes of one-minute bars that valued Binance operations. The sync stores each close the
  * first time it needs it, so that translating a record again reads the price it was first
  * translated with, though Binance can no longer serve the bar.
  *
  * Prices are the market's and not an account's, so no account is involved.
  */
trait BinanceMinutePriceStorage[F[_]] {

  /** The stored close of each of `minutes` that has one; a minute with none is left out. */
  def closes(minutes: Set[BinanceMinute]): F[Map[BinanceMinute, BigDecimal]]

  /** Inserts each price, or replaces the one stored for the same pair and minute. */
  def save(prices: List[BinanceMinutePrice]): F[Unit]
}
