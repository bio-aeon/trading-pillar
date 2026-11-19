package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.binance.BinancePair

/** The spot pairs that trades have been read on. A trade names only its symbol, and Binance drops a
  * delisted pair from its exchange information, so the two assets of each pair are kept to
  * translate a stored trade again.
  *
  * Pairs are the market's and not an account's, so no account is involved.
  */
trait BinancePairStorage[F[_]] {

  /** Inserts each pair, or replaces the one stored under the same symbol. */
  def upsert(pairs: List[BinancePair]): F[Unit]

  /** Every stored pair, in no particular order. */
  def all: F[List[BinancePair]]
}
