package st.btu.trading.pillar.analytics

import cats.{Functor, Monad}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.MarketDataSources
import st.btu.trading.pillar.models.market.{Bar, Source, Ticker}
import st.btu.trading.pillar.storages.PriceHistoryStorage

import java.time.LocalDate

// A ticker's stored bars, read only from the source the ticker is configured to use. Bars another
// source stored for the same ticker are a different series and are never mixed in or used as a
// fallback.
private[pillar] object ConfiguredBars {

  // Chronological, over [from, to].
  def load[F[_]: Monad](
    priceHistory: PriceHistoryStorage[F],
    sources: MarketDataSources[F],
    ticker: Ticker,
    from: LocalDate,
    to: LocalDate
  ): F[Vector[Bar]] =
    MarketDataSources
      .sourceOf(sources, ticker)
      .flatMap(loadFrom(priceHistory, _, ticker, from, to))

  // Like `load`, for a caller that has already resolved the ticker's configured `source`.
  def loadFrom[F[_]: Functor](
    priceHistory: PriceHistoryStorage[F],
    source: Source,
    ticker: Ticker,
    from: LocalDate,
    to: LocalDate
  ): F[Vector[Bar]] =
    priceHistory.bars(ticker, source, from, to).map(_.toVector.sortBy(_.date.toEpochDay))
}
