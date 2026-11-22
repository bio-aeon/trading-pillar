package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.binance.{BinanceAsset, BinanceBalances, BinancePair}
import st.btu.trading.pillar.models.domain.{Broker, BrokerInstrumentId, Currency, InstrumentKey}

// The comparison of the balances recorded for a Binance account with the balances Binance reports.
// Pure: `BinanceReconciler` reads what was recorded and acts on the differences.
private[services] object BinanceReconciliation {

  private val Zero = BigDecimal(0)

  // The prefix of the tokens that Simple Earn shows in the spot wallet for the flexible positions
  // an asset has been subscribed to.
  private val EarnPrefix = "LD"

  /** An asset whose balance at Binance is not what the account recorded.
    *
    * @param recorded
    *   what the account's operations leave: the position of a coin, or the cash in a cash asset;
    *   zero when they leave none
    * @param reported
    *   what Binance reports in the two wallets together; zero when it reports none
    */
  final case class Difference(asset: BinanceAsset, recorded: BigDecimal, reported: BigDecimal)

  /** The assets whose balance in the spot and the funding wallet together is not what the account
    * recorded, ordered by asset: the position in `positions` for a coin, the cash balance in `cash`
    * for an asset in `cashAssets`. An asset on one side only counts as zero on the other.
    *
    * A spot asset named `LD` and another asset is a Simple Earn token and left out, as long as no
    * listed pair trades it and a listed pair trades the other asset: `LDBTC` is, and `LDO`, a coin
    * of its own, is not.
    */
  def differences(
    positions: Map[InstrumentKey, BigDecimal],
    cash: Map[Currency, BigDecimal],
    balances: BinanceBalances,
    cashAssets: Set[BinanceAsset],
    listed: Iterable[BinancePair]
  ): List[Difference] = {
    val traded = listed.flatMap(pair => List(pair.baseAsset, pair.quoteAsset)).toSet
    val spot = balances.spot.filterNot { case (asset, _) => isEarnToken(asset, traded) }
    val reported = (spot.toList ++ balances.funding.toList)
      .groupMapReduce { case (asset, _) => asset } { case (_, amount) => amount }(_ + _)
    val coins = positions.keySet.filter(_.broker == Broker.Binance).map(assetOf)
    val cashHeld = cashAssets.filter(asset => cash.contains(Currency(asset.value)))

    def recordedOf(asset: BinanceAsset): BigDecimal =
      if (cashAssets(asset)) cash.getOrElse(Currency(asset.value), Zero)
      else positions.getOrElse(InstrumentKey(Broker.Binance, BrokerInstrumentId(asset.value)), Zero)

    (reported.keySet ++ coins ++ cashHeld).toList.sorted.flatMap { asset =>
      val recorded = recordedOf(asset)
      val onBinance = reported.getOrElse(asset, Zero)
      Option.when(recorded != onBinance)(Difference(asset, recorded, onBinance))
    }
  }

  private def assetOf(key: InstrumentKey): BinanceAsset = BinanceAsset(key.id.value)

  private def isEarnToken(asset: BinanceAsset, traded: Set[BinanceAsset]): Boolean =
    asset.value.startsWith(EarnPrefix) && !traded(asset) &&
      traded(BinanceAsset(asset.value.stripPrefix(EarnPrefix)))
}
