package st.btu.trading.pillar.gateways.news

import st.btu.trading.pillar.models.market.Ticker

import java.time.ZonedDateTime

/** One news item about a ticker. */
final case class NewsItem(
  title: String,
  publisher: Option[String],
  link: String,
  publishedAt: Option[ZonedDateTime]
)

/** Recent news headlines for a ticker. */
trait NewsSource[F[_]] {

  /** The `count` most recent news items for `ticker`. */
  def news(ticker: Ticker, count: Int): F[List[NewsItem]]
}
