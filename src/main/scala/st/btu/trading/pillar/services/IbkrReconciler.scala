package st.btu.trading.pillar.services

import cats.Monad
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.ibkr.IbkrOpenPositions
import st.btu.trading.pillar.services.IbkrReconciliation.Difference
import st.btu.trading.pillar.storages.{AccountOperationStorage, BrokerInstrumentStorage}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.LocalDate

/** Compares the positions recorded for IB accounts with the Open Positions IB reports, and raises a
  * system alert while they differ. A difference means a gap in how the rows of an account's
  * statements are translated: a corporate action that was translated wrongly, or a row that IB
  * reported late or never.
  *
  * What was raised is kept in memory, so after a restart the differences that are still there are
  * raised once more.
  */
final class IbkrReconciler[F[_]: Logging] private (
  operations: AccountOperationStorage[F],
  instruments: BrokerInstrumentStorage[F],
  systemAlerts: SystemAlerts[F],
  raised: Ref[F, Map[AccountId, List[Difference]]]
)(implicit F: Monad[F]) {
  import IbkrReconciler.*

  /** Compares the positions the executed operations of `accountId` leave at the end of
    * `reported.day`, in `reported.zone`, with the stock positions in `reported`, per contract.
    *
    * Differences are raised as the system alert `ibkr-reconciliation:<account id>`, naming each
    * instrument with its symbol, contract id and both quantities, and raised again only when they
    * change. Once they clear, `ibkr-reconciled:<account id>` follows. An instrument IB does not
    * report is named by the symbol of its stored record, or by its contract id alone when there is
    * none.
    */
  def reconcile(accountId: AccountId, reported: IbkrOpenPositions): F[Unit] =
    for {
      holdings <- AccountHoldings.before(operations, accountId, reported.end)
      differences <- IbkrReconciliation.differences(holdings, reported).traverse(withSymbol)
      previous <- raised.get.map(_.getOrElse(accountId, Nil))
      _ <- info"Account ${accountId.value}: ${differences.size} positions differ from IB's"
      _ <- announce(accountId, reported.day, previous, differences)
      _ <- raised.update(_.updated(accountId, differences))
    } yield ()

  // A contract the statement does not report has no symbol in it.
  private def withSymbol(difference: Difference): F[Difference] =
    if (difference.symbol.isDefined) F.pure(difference)
    else
      instruments
        .find(difference.instrument)
        .map(stored => difference.copy(symbol = stored.map(_.ticker)))

  // Raising comes before remembering what was raised, so that an alert that fails to go out is
  // tried again at the next sync.
  private def announce(
    accountId: AccountId,
    day: LocalDate,
    before: List[Difference],
    now: List[Difference]
  ): F[Unit] =
    if (now.isEmpty)
      F.whenA(before.nonEmpty)(
        systemAlerts.raise(
          s"$MatchedAlert:${accountId.value}",
          s"IB positions match: account ${accountId.value}",
          s"Recorded positions of account ${accountId.value} match the Open Positions IB " +
            s"reports at the end of $day."
        )
      )
    else
      F.whenA(now != before)(
        systemAlerts.raise(
          s"$DifferentAlert:${accountId.value}",
          s"IB positions differ: account ${accountId.value}",
          differencesText(accountId, day, now)
        )
      )
}

object IbkrReconciler {

  private val DifferentAlert = "ibkr-reconciliation"
  private val MatchedAlert = "ibkr-reconciled"

  private def differencesText(
    accountId: AccountId,
    day: LocalDate,
    differences: List[Difference]
  ): String =
    differences
      .map(difference =>
        s"${nameOf(difference)}: recorded ${plain(difference.recorded)}, " +
          s"IB reports ${plain(difference.reported)}"
      )
      .mkString(
        s"Recorded positions of account ${accountId.value} differ from the Open Positions IB " +
          s"reports at the end of $day:\n",
        "\n",
        ""
      )

  private def nameOf(difference: Difference): String = {
    val contract = s"contract ${difference.instrument.id.value}"
    difference.symbol.fold(contract)(symbol => s"${symbol.value} ($contract)")
  }

  // The digits of a quantity without trailing zeros or an exponent, whatever scale it carries.
  private def plain(quantity: BigDecimal): String =
    quantity.bigDecimal.stripTrailingZeros.toPlainString

  /** Wires a reconciler that reads the recorded positions from `operations`, finds the symbol of an
    * instrument IB does not report in `instruments`, and raises its alerts through `systemAlerts`.
    */
  def create[I[_]: Sync, F[_]: Sync](
    operations: AccountOperationStorage[F],
    instruments: BrokerInstrumentStorage[F],
    systemAlerts: SystemAlerts[F]
  )(implicit logs: Logs[I, F]): I[IbkrReconciler[F]] =
    for {
      raised <- Ref.in[I, F, Map[AccountId, List[Difference]]](Map.empty)
      reconciler <- logs
        .forService[IbkrReconciler[F]]
        .map(implicit log => new IbkrReconciler[F](operations, instruments, systemAlerts, raised))
    } yield reconciler
}
