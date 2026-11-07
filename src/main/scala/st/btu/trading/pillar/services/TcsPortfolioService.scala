package st.btu.trading.pillar.services

import java.time.ZonedDateTime
import scala.concurrent.duration.FiniteDuration

/** Brings the stored operations of one Tinkoff account up to date. The account is the one in the
  * `ProcessContext` of the call.
  */
trait TcsPortfolioService[F[_]] {

  /** Fetches the account's operations of one window and records each with its translation,
    * replacing what is stored for an operation fetched before.
    *
    * The window reaches from `overlap` before `lastDt` to `overlap` after the earlier of now and
    * `lastDt` plus `interval`. It reaches back to the start of the configured re-read window
    * instead when that is earlier, so that an operation Tinkoff reports late, or changes after the
    * sync passed its date, is fetched again.
    *
    * @param lastDt
    *   the point the previous call reached; `None` starts at the newest stored operation, or at the
    *   configured number of years back when none is stored
    * @return
    *   the earlier of now and `lastDt` plus `interval`, before the overlap is added, to pass as
    *   `lastDt` to the next call
    */
  def syncOperations(
    interval: FiniteDuration,
    overlap: FiniteDuration,
    lastDt: Option[ZonedDateTime] = None
  ): F[ZonedDateTime]

  /** Records every operation stored for the account again, each with its translation, which
    * replaces the stored translation and leaves the stored operation as it is. It brings the
    * translations up to date after a change to how operations are translated.
    */
  def rebuildTranslations: F[Unit]
}
