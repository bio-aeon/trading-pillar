package st.btu.trading.pillar.gateways.news

import cats.Functor
import cats.syntax.functor.*
import io.github.coinductive.yfinance4s.YFinanceClient
import io.github.coinductive.yfinance4s.models.NewsItem as YfNewsItem

/** Thin seam over `YFinanceClient.search` (news only), expressed in `NewsSource`'s own `NewsItem`
  * so tests can fake it without building yfinance4s models.
  */
trait YahooNewsApi[F[_]] {

  /** Searches for `query`, keeping only the news results (quote/list results are discarded), up to
    * `newsCount`.
    */
  def search(query: String, newsCount: Int): F[List[NewsItem]]
}

object YahooNewsApi {

  /** Adapts a raw `YFinanceClient` to [[YahooNewsApi]]. */
  def fromClient[F[_]: Functor](client: YFinanceClient[F]): YahooNewsApi[F] = new YahooNewsApi[F] {
    def search(query: String, newsCount: Int): F[List[NewsItem]] =
      client.search(query, newsCount = newsCount).map(_.news.map(toNewsItem))
  }

  private def toNewsItem(item: YfNewsItem): NewsItem =
    NewsItem(
      item.title,
      Option(item.publisher).filter(_.nonEmpty),
      item.link,
      Some(item.publishTime)
    )
}
