package st.btu.trading.pillar.gateways.screener

import cats.MonadThrow
import io.github.coinductive.yfinance4s.models.PredefinedScreen as YfPredefinedScreen
import st.btu.trading.pillar.gateways.yahoo.YahooErrors

// Maps trading-pillar's 3-value PredefinedScreen onto yfinance4s's own predefined screens:
// Gainers -> DayGainers, Losers -> DayLosers, Active -> MostActives (yfinance4s also has
// AggressiveSmallCaps, GrowthTechnologyStocks, MostShortedStocks, SmallCapGainers,
// UndervaluedGrowthStocks/LargeCaps and several fund screens, none of which this command exposes).
// Errors go through YahooErrors with no ticker (a screener call has no single ticker to attach
// NotFound to).
final class YahooScreener[F[_]](api: YahooScreenerApi[F])(implicit F: MonadThrow[F])
    extends Screener[F] {

  def predefined(screen: PredefinedScreen, count: Int): F[List[ScreenerRow]] =
    F.adaptError(api.predefined(toYfScreen(screen), count))(YahooErrors.toMarketDataError(None))

  private def toYfScreen(screen: PredefinedScreen): YfPredefinedScreen = screen match {
    case PredefinedScreen.Gainers => YfPredefinedScreen.DayGainers
    case PredefinedScreen.Losers => YfPredefinedScreen.DayLosers
    case PredefinedScreen.Active => YfPredefinedScreen.MostActives
  }
}

object YahooScreener {
  def apply[F[_]: MonadThrow](api: YahooScreenerApi[F]): YahooScreener[F] =
    new YahooScreener[F](api)
}
