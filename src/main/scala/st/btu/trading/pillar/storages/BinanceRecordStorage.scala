package st.btu.trading.pillar.storages

import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.models.domain.AccountId

import java.time.Instant

/** Persisted records of Binance accounts as Binance reported them, each recorded together with its
  * translation into [[st.btu.trading.pillar.models.domain.AccountOperation]]s.
  */
trait BinanceRecordStorage[F[_]] {

  /** Stores each record. A record not stored yet is inserted. One stored already, under the same
    * account, source and record id, has its occurrence and attributes replaced, so a record that
    * Binance reports again, in another state, overwrites the earlier one.
    *
    * The record's translations are replaced as a whole. They are the operations of the account
    * whose `ref` is the record's `ref` (`BinanceRecord.ref`, such as `trade/BTCUSDC/28457`), or the
    * record's `ref` followed by a slash and a suffix (`trade/BTCUSDC/28457/fee`). The record's
    * translations are stored, each keyed by its account and `ref`, and the stored translations of
    * the record that are not among them are removed, so that one the record no longer produces does
    * not stay. Match the prefix literally and not as a pattern: a source's value can contain an
    * underscore, which a pattern reads as a wildcard.
    *
    * A record and its translations are stored, and the stale ones removed, in the same transaction,
    * so a failure leaves none of it done.
    */
  def record(records: List[BinanceTranslatedRecord]): F[Unit]

  /** The latest `occurredAt` among the records of `source` stored for `accountId`, or `None` if
    * there is none.
    */
  def latestOccurredAt(accountId: AccountId, source: BinanceSource): F[Option[Instant]]

  /** Every record stored for `accountId`, in no particular order. */
  def records(accountId: AccountId): F[Vector[BinanceRecord]]

  /** For each pair that `accountId` has stored trades on, the highest trade id among them: the
    * highest `id` attribute of the stored trades with that `symbol` attribute. A pair with no
    * stored trade is not among them.
    */
  def lastTradeIds(accountId: AccountId): F[Map[BinanceSymbol, BinanceTradeId]]
}
