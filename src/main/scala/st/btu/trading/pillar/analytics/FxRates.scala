package st.btu.trading.pillar.analytics

import cats.Functor
import cats.syntax.functor.*
import st.btu.trading.pillar.models.market.{Bar, Source, Ticker}
import st.btu.trading.pillar.storages.PriceHistoryStorage

import java.time.LocalDate

// Shared "<CCY><BASE>=X" FX-rate convention used by analytics.portfolio.PortfolioValuationServiceImpl
// and tax.TaxServiceImpl: a currency's conversion rate on a given date is approximated by the
// latest close of that pair in PriceHistoryStorage at or before the date - not the official/CBR
// rate for that exact instant. See docs/analytics.md and docs/tax.md for where this approximation
// is documented to each package's own readers.
object FxRates {

  def pairTicker(currency: String, baseCurrency: String): Ticker =
    Ticker(s"$currency${baseCurrency}=X")

  // Latest close at or before `date`. `bars` must already be sorted chronologically (ascending) -
  // see `loadBars` below, which guarantees that for both of this function's callers.
  def closeOnOrBefore(bars: Vector[Bar], date: LocalDate): Option[BigDecimal] =
    bars.reverseIterator.find(!_.date.isAfter(date)).map(_.close)

  // One currency's bars for the `<currency><baseCurrency>=X` pair, chronologically sorted, over
  // [from, to].
  def loadBars[F[_]: Functor](
    priceHistory: PriceHistoryStorage[F],
    currency: String,
    baseCurrency: String,
    source: Source,
    from: LocalDate,
    to: LocalDate
  ): F[Vector[Bar]] =
    priceHistory
      .bars(pairTicker(currency, baseCurrency), source, from, to)
      .map(_.toVector.sortBy(_.date.toEpochDay))
}
