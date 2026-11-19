package st.btu.trading.pillar.models.binance

/** A spot trading pair with the two assets it trades. A trade names only its symbol, and a pair
  * that Binance has delisted is gone from its exchange information, so the assets are kept to
  * translate a stored trade again.
  */
final case class BinancePair(
  symbol: BinanceSymbol,
  baseAsset: BinanceAsset,
  quoteAsset: BinanceAsset
)
