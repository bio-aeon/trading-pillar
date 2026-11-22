package st.btu.trading.pillar.services

import cats.Monad
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.alerts.SystemAlerts
import st.btu.trading.pillar.analytics.portfolio.Holdings
import st.btu.trading.pillar.models.binance.{BinanceAsset, BinanceBalances, BinancePair}
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.services.BinanceReconciliation.Difference
import st.btu.trading.pillar.services.commands.Replies
import st.btu.trading.pillar.storages.AccountOperationStorage
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.Instant

/** Compares the balances recorded for Binance accounts with the balances Binance reports, and
  * raises a system alert while they differ. A difference means a gap in what the API gives: card
  * spending from the funding wallet, Simple Earn subscriptions and their rewards, trades on a pair
  * that was delisted before they were read, history from before what the distribution and dust
  * endpoints keep, or a deposit or a withdrawal still in progress.
  *
  * The balance of an asset is its amount in the spot and the funding wallet together. It is
  * compared with the position the account's executed operations leave in a coin, or with the cash
  * they leave in an asset of `cashAssets`. Only the operations that occurred before the instant the
  * balances were read count, since a later one may not have reached them. An asset that is on one
  * side only counts as zero on the other.
  *
  * What was raised is kept in memory, so after a restart the differences that are still there are
  * raised once more.
  */
final class BinanceReconciler[F[_]: Logging] private (
  operations: AccountOperationStorage[F],
  systemAlerts: SystemAlerts[F],
  cashAssets: Set[BinanceAsset],
  raised: Ref[F, Map[AccountId, List[Difference]]]
)(implicit F: Monad[F]) {
  import BinanceReconciler.*

  /** The coins, which are the assets that are not cash, whose balance in `balances` differs from
    * what the operations of `accountId` that occurred before `asOf` leave, by a difference that the
    * last reconciliation of the account did not find. A difference that stays the same is given
    * once, and a new trade of the coin changes its balance and so its difference. After a restart
    * every difference is new. Raises nothing.
    *
    * @param listed
    *   the pairs Binance lists, which tell Simple Earn's tokens, left out, from coins
    */
  def differingCoins(
    accountId: AccountId,
    balances: BinanceBalances,
    listed: Iterable[BinancePair],
    asOf: Instant
  ): F[Set[BinanceAsset]] =
    for {
      differences <- differencesOf(accountId, balances, listed, asOf)
      found <- raised.get.map(_.getOrElse(accountId, Nil).toSet)
    } yield differences.filterNot(found).map(_.asset).filterNot(cashAssets).toSet

  /** Compares the balances in `balances`, read at `asOf`, with what the operations of `accountId`
    * that occurred before `asOf` leave.
    *
    * Differences are raised as the system alert `binance-reconciliation:<account id>`, naming each
    * asset with the amount recorded and the amount Binance reports, and the instant `asOf`, and
    * raised again only when they change. Once they clear, `binance-reconciled:<account id>`
    * follows.
    *
    * @param listed
    *   the pairs Binance lists, which tell Simple Earn's tokens, left out, from coins
    */
  def reconcile(
    accountId: AccountId,
    balances: BinanceBalances,
    listed: Iterable[BinancePair],
    asOf: Instant
  ): F[Unit] =
    for {
      differences <- differencesOf(accountId, balances, listed, asOf)
      previous <- raised.get.map(_.getOrElse(accountId, Nil))
      _ <- info"Account ${accountId.value}: ${differences.size} balances differ from Binance's"
      _ <- announce(accountId, asOf, previous, differences)
      _ <- raised.update(_.updated(accountId, differences))
    } yield ()

  private def differencesOf(
    accountId: AccountId,
    balances: BinanceBalances,
    listed: Iterable[BinancePair],
    asOf: Instant
  ): F[List[Difference]] =
    AccountHoldings.operationsBefore(operations, accountId, asOf).map { history =>
      BinanceReconciliation.differences(
        Holdings.of(history),
        AccountHoldings.cashOf(history),
        balances,
        cashAssets,
        listed
      )
    }

  // Raising comes before remembering what was raised, so that an alert that fails to go out is
  // tried again at the next sync.
  private def announce(
    accountId: AccountId,
    asOf: Instant,
    before: List[Difference],
    now: List[Difference]
  ): F[Unit] =
    if (now.isEmpty)
      F.whenA(before.nonEmpty)(
        systemAlerts.raise(
          s"$MatchedAlert:${accountId.value}",
          s"Binance balances match: account ${accountId.value}",
          s"Recorded balances of account ${accountId.value} match the balances Binance reports as " +
            s"of $asOf."
        )
      )
    else
      F.whenA(now != before)(
        systemAlerts.raise(
          s"$DifferentAlert:${accountId.value}",
          s"Binance balances differ: account ${accountId.value}",
          differencesText(accountId, asOf, now)
        )
      )
}

object BinanceReconciler {

  private val DifferentAlert = "binance-reconciliation"
  private val MatchedAlert = "binance-reconciled"

  private def differencesText(
    accountId: AccountId,
    asOf: Instant,
    differences: List[Difference]
  ): String =
    differences
      .map(difference =>
        s"${difference.asset.value}: recorded ${Replies.plain(difference.recorded)}, " +
          s"Binance ${Replies.plain(difference.reported)}"
      )
      .mkString(
        s"Recorded balances of account ${accountId.value} differ from the balances Binance " +
          s"reports as of $asOf:\n",
        "\n",
        ""
      )

  /** Wires a reconciler that reads the recorded balances from `operations`, treats the assets in
    * `cashAssets` as cash, and raises its alerts through `systemAlerts`. `cashAssets` is the set
    * the accounts are synced with.
    */
  def create[I[_]: Sync, F[_]: Sync](
    operations: AccountOperationStorage[F],
    systemAlerts: SystemAlerts[F],
    cashAssets: Set[BinanceAsset]
  )(implicit logs: Logs[I, F]): I[BinanceReconciler[F]] =
    for {
      raised <- Ref.in[I, F, Map[AccountId, List[Difference]]](Map.empty)
      reconciler <- logs
        .forService[BinanceReconciler[F]]
        .map(implicit log => new BinanceReconciler[F](operations, systemAlerts, cashAssets, raised))
    } yield reconciler
}
