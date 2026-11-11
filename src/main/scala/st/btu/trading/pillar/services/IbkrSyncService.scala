package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.ibkr.IbkrOpenPositions

/** Brings the stored statement rows of one IB account up to date. */
trait IbkrSyncService[F[_]] {

  /** Reads the account's statements from the day the configuration says up to the day before today,
    * window by window, and records each window's rows with their translations, and the instruments
    * its statement describes, before it reads the next.
    *
    * With no row stored, the first window starts on `IbkrSyncConfig.historyStart`, but never before
    * the earliest date IB keeps (`IbkrSyncConfig.earliestHistory`); with rows stored, it starts the
    * configured number of days before the latest stored report date. A window that IB cannot
    * generate is read again as two halves, down to a single day.
    *
    * @return
    *   the positions the statement of the last window read reports as open at the end of the last
    *   day that statement covers, or `None` when no window was read; a failed `F` when a statement
    *   cannot be read, when it is not the configured account's, or when one of its rows cannot be
    *   translated
    */
  def sync: F[Option[IbkrOpenPositions]]

  /** Records every row stored for the account again, each with its translation, which replaces the
    * stored translations and leaves the stored rows as they are. It brings the translations up to
    * date after a change to how rows are translated.
    */
  def rebuildTranslations: F[Unit]
}
