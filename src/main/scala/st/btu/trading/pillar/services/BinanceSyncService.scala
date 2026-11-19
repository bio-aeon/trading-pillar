package st.btu.trading.pillar.services

/** Brings the stored records of one Binance account up to date. */
trait BinanceSyncService[F[_]] {

  /** Reads what Binance holds of the account since the last read, and records each record with its
    * translation, and the pairs and the instruments they need, before it reads the next.
    *
    * A run starts by checking that the API key can read and do nothing else, and fails with a
    * `BinanceKeyError` before any other call when it can do more. It then reads the exchange
    * information and the sources other than trades, window by window, and then the trades.
    *
    * Each source other than trades is read from `BinanceSyncConfig.rereadDays` before where this
    * process last read it, so that a record Binance completes or changes late is stored again.
    * After a restart that is `rereadDays` before the latest record stored for the source, and with
    * none stored it is the start of `BinanceSyncConfig.historyStart`. The windows are 89 days long,
    * 30 for Convert, and each ends a millisecond before the next starts.
    *
    * Trades are read pair by pair, each from the id after the last stored for it: the pairs that
    * have stored trades, the pairs of a coin the sources above gave the account in this run, and
    * the pairs Binance has announced it will delist, each while Binance still lists it, since a
    * pair Binance has dropped cannot be read. The first run of a process, and then one every
    * `BinanceSyncConfig.pairsRescanInterval`, reads every listed pair, to find the pairs the
    * account has traded for the first time.
    *
    * What no cash prices, such as a trade between two coins or a commission paid in BNB, is valued
    * by the close of a one-minute bar, which is read the first time it is needed and stored. The
    * records that no bar could value are raised as the system alert `binance-unvalued:<account
    * id>`, once for each in this process.
    *
    * @return
    *   a failed `F` when the key can do more than read, when Binance cannot be read, or when a
    *   record cannot be translated
    */
  def sync: F[Unit]

  /** Records every record stored for the account again, each with its translation made from the
    * stored pairs and prices, which replaces the stored translations and leaves the stored records
    * as they are. It brings the translations up to date after a change to how records are
    * translated, and raises the alert for the records that no price could value, as `sync` does.
    * The instruments of the coins its translations name are written by the next `sync`, which reads
    * the pairs Binance lists that a coin's ticker is chosen from.
    */
  def rebuildTranslations: F[Unit]
}
