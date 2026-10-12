package st.btu.trading.pillar.gateways.yahoo

import cats.Functor
import cats.syntax.functor.*
import io.github.coinductive.yfinance4s.YFinanceClient
import io.github.coinductive.yfinance4s.models.{Ticker as YfTicker, *}

import java.time.ZonedDateTime

/** One daily OHLCV quote from yfinance4s, before conversion to this library's own `Bar`. */
final case class YahooQuote(
  datetime: ZonedDateTime,
  open: Double,
  high: Double,
  low: Double,
  close: Double,
  adjClose: Double,
  volume: Long
)

/** A ticker's daily quotes, plus the currency they are denominated in. */
final case class YahooChart(quotes: List[YahooQuote], currency: String)

/** Instrument and latest-quote metadata from yfinance4s, before conversion to this library's own
  * `InstrumentInfo`/`Quote`.
  */
final case class YahooMetadata(
  symbol: String,
  exchangeName: String,
  instrumentType: String,
  currency: String,
  exchangeTimezoneName: String,
  regularMarketTime: Option[ZonedDateTime],
  regularMarketPrice: Option[Double]
)

/** One dividend event from yfinance4s, before conversion to this library's own `DividendEvent`. */
final case class YahooDividend(exDate: ZonedDateTime, amount: Double, currency: Option[String])

/** Thin seam over `YFinanceClient.charts`, expressed in small library-owned DTOs so tests can fake
  * it without building yfinance4s models.
  */
trait YahooApi[F[_]] {

  /** Daily OHLCV quotes for `ticker` between `since` and `until`, or `None` if there is no chart
    * data at all.
    */
  def dailyChart(ticker: String, since: ZonedDateTime, until: ZonedDateTime): F[Option[YahooChart]]

  /** Instrument and latest-quote metadata for `ticker`; also the source of `latestQuote`. */
  def metadata(ticker: String): F[YahooMetadata]

  /** Dividend events for `ticker` between `since` and `until`; empty if there is no chart data at
    * all.
    */
  def dividends(ticker: String, since: ZonedDateTime, until: ZonedDateTime): F[List[YahooDividend]]
}

object YahooApi {

  /** Adapts a raw `YFinanceClient` to [[YahooApi]]. */
  def fromClient[F[_]: Functor](client: YFinanceClient[F]): YahooApi[F] = new YahooApi[F] {
    def dailyChart(
      ticker: String,
      since: ZonedDateTime,
      until: ZonedDateTime
    ): F[Option[YahooChart]] =
      client.charts
        .getChart(YfTicker(ticker), Interval.`1Day`, since, until)
        .map(_.map(toYahooChart))

    def metadata(ticker: String): F[YahooMetadata] =
      client.charts.getHistoryMetadata(YfTicker(ticker)).map(toYahooMetadata)

    def dividends(
      ticker: String,
      since: ZonedDateTime,
      until: ZonedDateTime
    ): F[List[YahooDividend]] =
      client.charts
        .getDividends(YfTicker(ticker), Interval.`1Day`, since, until)
        .map(_.fold(List.empty[YahooDividend])(_.map(toYahooDividend)))
  }

  private def toYahooChart(chart: ChartResult): YahooChart =
    YahooChart(chart.quotes.map(toYahooQuote), chart.currency)

  private def toYahooQuote(quote: ChartResult.Quote): YahooQuote =
    YahooQuote(
      quote.datetime,
      quote.open,
      quote.high,
      quote.low,
      quote.close,
      quote.adjclose,
      quote.volume
    )

  private def toYahooMetadata(meta: HistoryMetadata): YahooMetadata =
    YahooMetadata(
      meta.symbol,
      meta.exchangeName,
      meta.instrumentType.raw,
      meta.currency,
      meta.exchangeTimezoneName,
      meta.regularMarketTime,
      meta.regularMarketPrice
    )

  private def toYahooDividend(d: DividendEvent): YahooDividend =
    YahooDividend(d.exDate, d.amount, d.currency)
}
