package st.btu.trading.pillar.gateways.screener

import cats.Functor
import cats.syntax.functor.*
import io.github.coinductive.yfinance4s.YFinanceClient
import io.github.coinductive.yfinance4s.models.{
  PredefinedScreen as YfPredefinedScreen,
  ScreenerQuote
}
import st.btu.trading.pillar.models.market.{Prices, Ticker}

/** Thin seam over `YFinanceClient.screener.screenPredefined`, expressed in `Screener`'s own
  * `ScreenerRow` so tests can fake it without building yfinance4s models. Takes yfinance4s's own
  * `PredefinedScreen` (its full set of named screens) rather than a parallel enum at this layer;
  * mapping trading-pillar's own three-value `PredefinedScreen` onto it is `YahooScreener`'s job,
  * one level up.
  */
trait YahooScreenerApi[F[_]] {

  /** The top `count` rows of `screen`. */
  def predefined(screen: YfPredefinedScreen, count: Int): F[List[ScreenerRow]]
}

object YahooScreenerApi {

  /** Adapts a raw `YFinanceClient` to [[YahooScreenerApi]]. */
  def fromClient[F[_]: Functor](client: YFinanceClient[F]): YahooScreenerApi[F] =
    new YahooScreenerApi[F] {
      def predefined(screen: YfPredefinedScreen, count: Int): F[List[ScreenerRow]] =
        client.screener.screenPredefined(screen, count).map(_.quotes.map(toScreenerRow))
    }

  // changePct is Yahoo's own regularMarketChangePercent, already a percentage value (e.g. 3.5
  // means +3.5%), not a 0..1 fraction.
  private def toScreenerRow(q: ScreenerQuote): ScreenerRow =
    ScreenerRow(
      Ticker(q.symbol),
      q.longName.orElse(q.shortName),
      q.regularMarketPrice.map(Prices.fromDouble),
      q.regularMarketChangePercent.map(Prices.fromDouble)
    )
}
