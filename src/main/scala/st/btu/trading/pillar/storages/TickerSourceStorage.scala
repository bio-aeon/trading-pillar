package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.market.{Ticker, TickerSource}

/** Per-ticker market data source settings. A ticker without a stored setting uses the default
  * source the router is configured with.
  */
trait TickerSourceStorage[F[_]] {

  /** `ticker`'s stored setting, or `None` if it has none. */
  def find(ticker: Ticker): F[Option[TickerSource]]

  /** Every stored setting. */
  def all: F[List[TickerSource]]

  /** Stores `entry`, replacing the setting its ticker had. */
  def upsert(entry: TickerSource): F[Unit]

  /** Removes `ticker`'s setting, so that it uses the default source again.
    *
    * @return
    *   whether a setting was actually removed
    */
  def delete(ticker: Ticker): F[Boolean]
}
