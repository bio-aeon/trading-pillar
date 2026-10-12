package st.btu.trading.pillar.gateways

import cats.MonadThrow
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.models.market.{MarketDataError, Source, Ticker}
import st.btu.trading.pillar.storages.WatchlistStorage

/** Routes a ticker, or a known source, to the `MarketDataSource` that serves it, so a single
  * consumer can support multiple brokers without hard-coding one.
  */
trait MarketDataSources[F[_]] {

  /** The configured source for `source`, if one is wired. */
  def forSource(source: Source): Option[MarketDataSource[F]]

  /** Resolves `ticker` to its market data source: the source of the watchlist item carrying it (on
    * any account's watchlist), falling back to the configured default when `ticker` isn't on any
    * watchlist.
    *
    * @return
    *   a failed `F` with `MarketDataError.Other` if the resolved source has no configured
    *   `MarketDataSource`
    */
  def forTicker(ticker: Ticker): F[MarketDataSource[F]]
}

final class MarketDataSourcesImpl[F[_]](
  sources: List[MarketDataSource[F]],
  watchlist: WatchlistStorage[F],
  default: Source
)(implicit F: MonadThrow[F])
    extends MarketDataSources[F] {

  private val bySource: Map[Source, MarketDataSource[F]] = sources.map(s => s.source -> s).toMap

  def forSource(source: Source): Option[MarketDataSource[F]] = bySource.get(source)

  def forTicker(ticker: Ticker): F[MarketDataSource[F]] =
    watchlist.findByTicker(ticker).map(_.fold(default)(_.source)).flatMap(resolve)

  private def resolve(source: Source): F[MarketDataSource[F]] =
    forSource(source) match {
      case Some(marketDataSource) => F.pure(marketDataSource)
      case None =>
        F.raiseError(
          MarketDataError.Other(s"Market data source not configured: ${source.value}", None)
        )
    }
}
