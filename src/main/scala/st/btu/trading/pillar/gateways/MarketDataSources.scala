package st.btu.trading.pillar.gateways

import cats.{Functor, MonadThrow}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.TickerSourceStorage

/** Routes each ticker to the `MarketDataSource` that its source setting selects, so a single
  * consumer can serve tickers from several providers without hard-coding one.
  */
trait MarketDataSources[F[_]] {

  /** The setting that applies to `ticker`: the one stored for it, or the default when it has none.
    */
  def settingFor(ticker: Ticker): F[SourceSetting]

  /** The adapter serving `ticker`, bound to its setting.
    *
    * @return
    *   a failed `F` with `MarketDataError.SourceNotConfigured` if the setting's source has no
    *   adapter configured
    */
  def forTicker(ticker: Ticker): F[MarketDataSource[F]]
}

object MarketDataSources {

  // The source a ticker's stored data is read from.
  private[pillar] def sourceOf[F[_]: Functor](
    sources: MarketDataSources[F],
    ticker: Ticker
  ): F[Source] =
    sources.settingFor(ticker).map(_.source)

  // The ticker paired with the source that serves it, the key for source-specific stored data.
  private[pillar] def sourced[F[_]: Functor](
    sources: MarketDataSources[F],
    ticker: Ticker
  ): F[SourcedTicker] =
    sourceOf(sources, ticker).map(SourcedTicker(ticker, _))
}

/** A [[MarketDataSources]] over at most one adapter per provider.
  *
  * @param yahoo
  *   the Yahoo adapter, if one is wired, which serves a ticker under the symbol its setting names
  * @param tinkoff
  *   the Tinkoff adapter, if one is wired, which serves a ticker from the board its setting names
  * @param settings
  *   the per-ticker settings
  * @param default
  *   the setting of every ticker that has none stored
  */
final class MarketDataSourcesImpl[F[_]](
  yahoo: Option[YahooSymbols[F]],
  tinkoff: Option[TinkoffBoards[F]],
  settings: TickerSourceStorage[F],
  default: SourceSetting
)(implicit F: MonadThrow[F])
    extends MarketDataSources[F] {

  def settingFor(ticker: Ticker): F[SourceSetting] =
    settings.find(ticker).map(_.fold(default)(_.setting))

  def forTicker(ticker: Ticker): F[MarketDataSource[F]] =
    settingFor(ticker).flatMap(adapterFor)

  private def adapterFor(setting: SourceSetting): F[MarketDataSource[F]] =
    setting match {
      case SourceSetting.Yahoo(symbol) =>
        configured(yahoo.map(_.onSymbol(symbol)), setting.source)
      case SourceSetting.Tinkoff(classCode) =>
        configured(tinkoff.map(_.onBoard(classCode)), setting.source)
    }

  private def configured(
    adapter: Option[MarketDataSource[F]],
    source: Source
  ): F[MarketDataSource[F]] =
    adapter match {
      case Some(marketDataSource) => F.pure(marketDataSource)
      case None => F.raiseError(MarketDataError.SourceNotConfigured(source))
    }
}
