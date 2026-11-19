package st.btu.trading.pillar.processes

import fs2.Stream

/** Keeps the stored records of every Binance account it is given up to date. */
trait BinanceSyncProcess[F[_]] {

  /** The sync loops of all the accounts, run side by side. Given no account, the stream ends at
    * once; otherwise it does not end by itself.
    */
  def run: Stream[F, Unit]
}
