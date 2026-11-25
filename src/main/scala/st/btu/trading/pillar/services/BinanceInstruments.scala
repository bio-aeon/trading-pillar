package st.btu.trading.pillar.services

import cats.data.NonEmptyList
import st.btu.trading.pillar.gateways.binance.BinanceMarketDataSource
import st.btu.trading.pillar.models.binance.{BinanceAsset, BinanceSymbol}
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.{BrokerInstrument, Ticker}

import java.time.ZonedDateTime

// The coins that operations name, as the broker instruments the directory answers from. Binance
// has no lookup of a coin by its code, so each coin is described from what the sync knows.
private[services] object BinanceInstruments {

  /** The Binance coins that `operations` name. */
  def coinsOf(operations: Iterable[AccountOperation]): Set[BinanceAsset] =
    operations.iterator
      .flatMap(_.instrument)
      .filter(_.broker == Broker.Binance)
      .map(key => BinanceAsset(key.id.value))
      .toSet

  /** A `BrokerInstrument` for each of `coins`, ordered by asset code.
    *
    * The ticker of a coin is its pair with the first of `priceQuotes` that is among `listed`, such
    * as `BTCUSDC`, or its pair with the first price quote when none is listed; the pair names the
    * coin apart from a stock of the same code, and is the pair whose prices the coin is read under.
    * The currency is that quote, and the name is the coin's in `names`, or its asset code when
    * `names` lacks it. A coin has no board.
    *
    * @param priceQuotes
    *   the cash assets coins are priced in, in order of preference
    * @param listed
    *   the pairs Binance lists
    */
  def of(
    coins: Set[BinanceAsset],
    names: Map[BinanceAsset, String],
    listed: Set[BinanceSymbol],
    priceQuotes: NonEmptyList[BinanceAsset],
    updatedAt: ZonedDateTime
  ): List[BrokerInstrument] =
    coins.toList.sorted.map { coin =>
      val quote = priceQuotes
        .find(quote => listed(BinanceSymbol.of(coin, quote)))
        .getOrElse(priceQuotes.head)
      BrokerInstrument(
        InstrumentKey(Broker.Binance, BrokerInstrumentId(coin.value)),
        Ticker(BinanceSymbol.of(coin, quote).value),
        None,
        names.getOrElse(coin, coin.value),
        BinanceMarketDataSource.CoinType,
        Currency(quote.value),
        updatedAt,
        futureTerms = None
      )
    }
}
