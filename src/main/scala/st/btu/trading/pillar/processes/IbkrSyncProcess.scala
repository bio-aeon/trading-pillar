package st.btu.trading.pillar.processes

import fs2.Stream

/** Keeps the stored statement rows of every Interactive Brokers account it is given up to date. */
trait IbkrSyncProcess[F[_]] {

  /** The sync loops of all the accounts, run side by side. Given no account, the stream ends at
    * once; otherwise it does not end by itself.
    */
  def run: Stream[F, Unit]
}
