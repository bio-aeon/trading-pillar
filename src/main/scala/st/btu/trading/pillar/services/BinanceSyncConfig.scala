package st.btu.trading.pillar.services

import cats.data.NonEmptyList
import st.btu.trading.pillar.models.binance.BinanceAsset

import java.time.LocalDate
import scala.concurrent.duration.*

/** What differs between deployments in how a Binance account is synced.
  *
  * @param cashAssets
  *   the assets that count as cash: each is a currency, kept as a balance of its own, and never an
  *   instrument with tax lots. They are the stablecoins the account holds, and the fiat currencies
  *   it trades against, such as `EUR`. Every other asset is a coin and an instrument.
  * @param priceQuotes
  *   the cash assets a coin is priced in, in order of preference: a coin is valued by its pair with
  *   the first of them that has a price, so `USDC` before `USDT` values a coin by its USDC pair
  *   where there is one. Each of them is a cash asset.
  * @param historyStart
  *   the first day a source with no record stored is read from, starting at midnight UTC. The
  *   default is the day Binance launched.
  * @param rereadDays
  *   how many days before where a source was last read every later read starts, so that a record
  *   Binance completes or changes late is stored again
  * @param pairsRescanInterval
  *   how long after one read of every pair the next is made, to find the pairs the account has
  *   traded for the first time
  */
final case class BinanceSyncConfig(
  cashAssets: Set[BinanceAsset] = BinanceSyncConfig.DefaultCashAssets,
  priceQuotes: NonEmptyList[BinanceAsset] = BinanceSyncConfig.DefaultPriceQuotes,
  historyStart: LocalDate = BinanceSyncConfig.LaunchDate,
  rereadDays: Int = 7,
  pairsRescanInterval: FiniteDuration = 1.day
) {
  require(priceQuotes.forall(cashAssets), "every price quote must be a cash asset")
  require(rereadDays >= 0, "rereadDays must not be negative")
}

object BinanceSyncConfig {

  /** The stablecoins that count as cash by default. */
  val DefaultCashAssets: Set[BinanceAsset] =
    Set("USDT", "USDC", "FDUSD", "BUSD").map(BinanceAsset(_))

  /** The default price quotes: USDC where a coin has a USDC pair, else USDT. */
  val DefaultPriceQuotes: NonEmptyList[BinanceAsset] =
    NonEmptyList.of("USDC", "USDT").map(BinanceAsset(_))

  /** The day Binance launched, 14 July 2017, before which an account has no history. */
  val LaunchDate: LocalDate = LocalDate.of(2017, 7, 14)
}
