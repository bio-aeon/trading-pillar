package st.btu.trading.pillar.gateways.news

import cats.MonadThrow
import st.btu.trading.pillar.gateways.yahoo.YahooErrors
import st.btu.trading.pillar.models.market.Ticker

// Maps YFinanceError -> MarketDataError via YahooErrors, same rules as YahooMarketDataSource.
final class YahooNewsSource[F[_]](api: YahooNewsApi[F])(implicit F: MonadThrow[F])
    extends NewsSource[F] {

  def news(ticker: Ticker, count: Int): F[List[NewsItem]] =
    F.adaptError(api.search(ticker.value, count))(YahooErrors.toMarketDataError(Some(ticker)))
}

object YahooNewsSource {
  def apply[F[_]: MonadThrow](api: YahooNewsApi[F]): YahooNewsSource[F] =
    new YahooNewsSource[F](api)
}
