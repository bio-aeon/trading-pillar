package st.btu.trading.pillar.models.binance

/** What Binance reports an account holds, in the two wallets that hold it, as the amount of each
  * asset. An asset with no balance is left out.
  *
  * @param spot
  *   the free and locked amounts in the spot wallet
  * @param funding
  *   the free, locked, frozen and withdrawing amounts in the funding wallet
  */
final case class BinanceBalances(
  spot: Map[BinanceAsset, BigDecimal],
  funding: Map[BinanceAsset, BigDecimal]
)
