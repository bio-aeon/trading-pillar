package st.btu.trading.pillar.analytics

import cats.Monad
import st.btu.trading.pillar.gateways.MarketDataSources
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.{Bar, Ticker}
import st.btu.trading.pillar.storages.PriceHistoryStorage

import java.time.LocalDate

/** Looks up the exchange rate of a currency in the stored bars of its rate ticker.
  *
  * A rate on a date is approximated by the latest close at or before that date, not the official
  * rate for that exact instant. The rate ticker is `<CCY><BASE>=X` unless a deployment names
  * another one for the currency, and its bars are read from the source that ticker is configured to
  * use.
  */
object FxRates {

  /** The conventional ticker carrying the rate from `currency` to `baseCurrency`, e.g. `USDRUB=X`.
    */
  def pairTicker(currency: Currency, baseCurrency: Currency): Ticker =
    Ticker(s"${currency.value}${baseCurrency.value}=X")

  /** The ticker whose bars carry `currency`'s rate: the one `fxTickers` names for it, otherwise the
    * conventional pair.
    */
  def rateTicker(
    currency: Currency,
    baseCurrency: Currency,
    fxTickers: Map[Currency, Ticker]
  ): Ticker =
    fxTickers.getOrElse(currency, pairTicker(currency, baseCurrency))

  /** The latest close at or before `date`. `bars` must already be sorted chronologically
    * (ascending), as [[loadBars]] returns them.
    */
  def closeOnOrBefore(bars: Vector[Bar], date: LocalDate): Option[BigDecimal] =
    bars.reverseIterator.find(!_.date.isAfter(date)).map(_.close)

  /** `currency`'s rate bars over `[from, to]`, chronologically sorted, read from the source its
    * rate ticker is configured to use.
    */
  def loadBars[F[_]: Monad](
    priceHistory: PriceHistoryStorage[F],
    sources: MarketDataSources[F],
    fxTickers: Map[Currency, Ticker],
    currency: Currency,
    baseCurrency: Currency,
    from: LocalDate,
    to: LocalDate
  ): F[Vector[Bar]] =
    ConfiguredBars.load(
      priceHistory,
      sources,
      rateTicker(currency, baseCurrency, fxTickers),
      from,
      to
    )
}
