package st.btu.trading.pillar.services

/** Keeps the instruments an account holds on its watchlist, with the rate tickers that convert its
  * currencies, so that they have price history.
  */
trait HeldInstrumentsService[F[_]] {

  /** For every stored account, adds the instruments it holds to its watchlist, and gives each held
    * ticker that has no source setting the one its broker's pricing rule gives. It then adds the
    * rate tickers of the account's currencies.
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
    * A held ticker that has no setting, and whose broker's rule finds no source to serve it, such
    * as a Binance coin while the router has no Binance adapter, is left off the watchlist, and the
    * service logs it.
    *
    * The rate tickers are the ones `FxRates.rateTicker` gives for each currency that the account's
    * executed operations are in, that a held instrument is priced in, or that a cost entered for a
    * transfer in is paid in, into each base currency other than itself, so that the bars the
    * valuation and the tax estimate convert with are synced. One the account's watchlist lacks,
    * enabled or not, is added enabled, with the note `Added for the exchange rate of <currency>`.
    * An entry already on the watchlist is left as it is, a disabled one included, and no source
    * setting is written for a rate ticker: it is read from the source its own setting names, else
    * from the default. A currency whose rate ticker has no bars stays unpriced in the valuation and
    * the tax estimate.
    *
    * @return
    *   a failed `F` when a storage fails
    */
  def addToWatchlists: F[Unit]
}
