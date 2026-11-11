package st.btu.trading.pillar.services

/** Keeps the instruments an account holds on its watchlist, so that they have price history. */
trait HeldInstrumentsService[F[_]] {

  /** For every stored account, adds the instruments it holds to its watchlist, and gives each held
    * ticker that has no source setting the one its broker's pricing rule gives.
    *
    * An account holds an instrument when the position its executed operations leave in it as of now
    * is not zero. Each held instrument is resolved through the instrument directory; one the
    * directory does not know is skipped, and so is one whose lookup fails, which is logged.
    *
    * A held ticker the account's watchlist lacks, enabled or not, is added enabled, with a note
    * that says the account holds it. An entry already on the watchlist is left as it is, a disabled
    * one included, and a sold holding's entry is not removed.
    *
    * A held ticker with no stored source setting gets the setting of its broker's pricing rule,
    * written before the ticker is added to the watchlist so that it is never synced from the wrong
    * source. The rule gives a setting only for a source the market data router serves, since one it
    * cannot serve would make every sync of the ticker fail. A stored setting is never changed.
    *
    * @return
    *   a failed `F` when a storage fails
    */
  def addToWatchlists: F[Unit]
}
