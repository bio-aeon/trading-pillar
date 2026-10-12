package st.btu.trading.pillar.gateways.yahoo

import cats.MonadThrow
import cats.effect.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.github.coinductive.yfinance4s.models.RateLimitConfig
import io.github.coinductive.yfinance4s.{YFinanceClient, YFinanceClientConfig}
import st.btu.trading.pillar.gateways.MarketDataSource
import st.btu.trading.pillar.models.market.*
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{Clock as _, *}
import scala.concurrent.duration.*

final class YahooMarketDataSource[F[_]: Logging] private (
  api: YahooApi[F],
  metadataCache: Ref[F, Map[Ticker, (YahooMetadata, Instant)]],
  metadataTtl: FiniteDuration
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends MarketDataSource[F] {

  def source: Source = Source.Yahoo

  def dailyBars(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[Bar]] =
    for {
      meta <- cachedMetadata(ticker)
      chart_? <- F.adaptError(
        api.dailyChart(
          ticker.value,
          since.atStartOfDay(ZoneOffset.UTC),
          until.plusDays(1).atStartOfDay(ZoneOffset.UTC)
        )
      )(mapError(ticker))
      bars <- chart_?.fold(F.pure(List.empty[Bar]))(chart => buildBars(ticker, meta, chart.quotes))
    } yield bars

  def latestQuote(ticker: Ticker): F[Option[Quote]] =
    for {
      meta <- fetchAndCacheMetadata(ticker)
      now <- clock.realTimeInstant.map(zonedUtc)
    } yield meta.regularMarketPrice.map { price =>
      Quote(
        ticker,
        Prices.fromDouble(price),
        meta.currency,
        meta.regularMarketTime.getOrElse(now),
        Source.Yahoo
      )
    }

  def instrumentInfo(ticker: Ticker): F[Option[InstrumentInfo]] =
    for {
      meta <- cachedMetadata(ticker)
      now <- clock.realTimeInstant.map(zonedUtc)
    } yield Some(
      InstrumentInfo(
        ticker,
        None,
        meta.instrumentType,
        meta.currency,
        meta.exchangeName,
        meta.exchangeTimezoneName,
        None,
        now
      )
    )

  def dividends(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[DividendEvent]] =
    for {
      meta <- cachedMetadata(ticker)
      divs <- F.adaptError(
        api.dividends(
          ticker.value,
          since.atStartOfDay(ZoneOffset.UTC),
          until.plusDays(1).atStartOfDay(ZoneOffset.UTC)
        )
      )(mapError(ticker))
    } yield buildDividends(ticker, meta, divs)

  private def cachedMetadata(ticker: Ticker): F[YahooMetadata] =
    for {
      now <- clock.realTimeInstant
      cached <- metadataCache.get.map(_.get(ticker))
      meta <- cached match {
        case Some((meta, fetchedAt)) if !expired(fetchedAt, now) => F.pure(meta)
        case _ => fetchAndCacheMetadata(ticker)
      }
    } yield meta

  private def fetchAndCacheMetadata(ticker: Ticker): F[YahooMetadata] =
    for {
      meta <- F.adaptError(api.metadata(ticker.value))(mapError(ticker))
      now <- clock.realTimeInstant
      _ <- metadataCache.update(_.updated(ticker, (meta, now)))
    } yield meta

  private def expired(fetchedAt: Instant, now: Instant): Boolean =
    now.isAfter(fetchedAt.plusNanos(metadataTtl.toNanos))

  private def zonedUtc(instant: Instant): ZonedDateTime =
    ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)

  private def buildBars(
    ticker: Ticker,
    meta: YahooMetadata,
    quotes: List[YahooQuote]
  ): F[List[Bar]] = {
    val zone = ZoneId.of(meta.exchangeTimezoneName)
    val (validQuotes, droppedCount) =
      quotes.foldLeft((List.empty[YahooQuote], 0)) {
        case ((acc, dropped), q) if isValidQuote(q) => (q :: acc, dropped)
        case ((acc, dropped), _) => (acc, dropped + 1)
      }

    val bars = validQuotes.reverse
      .foldLeft(scala.collection.immutable.ListMap.empty[LocalDate, Bar]) { (acc, q) =>
        val date = q.datetime.withZoneSameInstant(zone).toLocalDate
        acc.updated(date, toBar(ticker, date, q))
      }
      .values
      .toList
      .sortBy(_.date.toEpochDay)

    F.whenA(droppedCount > 0)(
      warn"Dropped $droppedCount invalid Yahoo quote(s) for ${ticker.value}"
    ) *> F.pure(bars)
  }

  // Date derivation mirrors buildBars: each ex-date is converted from UTC to a LocalDate in the
  // instrument's exchange timezone, not UTC or the caller's zone.
  private def buildDividends(
    ticker: Ticker,
    meta: YahooMetadata,
    divs: List[YahooDividend]
  ): List[DividendEvent] = {
    val zone = ZoneId.of(meta.exchangeTimezoneName)
    divs
      .map(d =>
        DividendEvent(
          ticker,
          d.exDate.withZoneSameInstant(zone).toLocalDate,
          Prices.fromDouble(d.amount),
          d.currency,
          Source.Yahoo
        )
      )
      .sortBy(_.exDate.toEpochDay)
  }

  private def isValidQuote(q: YahooQuote): Boolean = {
    def finitePositive(d: Double): Boolean = !d.isNaN && !d.isInfinite && d > 0

    finitePositive(q.open) && finitePositive(q.high) && finitePositive(q.low) &&
    finitePositive(q.close) && q.volume >= 0
  }

  private def toBar(ticker: Ticker, date: LocalDate, q: YahooQuote): Bar =
    Bar(
      ticker,
      date,
      Prices.fromDouble(q.open),
      Prices.fromDouble(q.high),
      Prices.fromDouble(q.low),
      Prices.fromDouble(q.close),
      Prices.fromDouble(q.adjClose),
      q.volume,
      Source.Yahoo
    )

  private def mapError(ticker: Ticker): PartialFunction[Throwable, Throwable] =
    YahooErrors.toMarketDataError(Some(ticker))
}

object YahooMarketDataSource {

  val DefaultMetadataTtl: FiniteDuration = 6.hours

  /** Connection and rate-limit settings for the underlying yfinance4s HTTP client. */
  final case class YahooConfig(
    connectTimeout: FiniteDuration,
    readTimeout: FiniteDuration,
    retries: Int,
    maxRequestsPerSecond: Int
  )

  /** Wires the underlying yfinance4s HTTP client, holding its connection pool open for the
    * resource's lifetime.
    */
  def clientResource[F[_]: Async](config: YahooConfig): Resource[F, YFinanceClient[F]] =
    YFinanceClient.resource[F](
      YFinanceClientConfig(
        config.connectTimeout,
        config.readTimeout,
        config.retries,
        RateLimitConfig.Enabled(config.maxRequestsPerSecond)
      )
    )

  /** Wires a [[MarketDataSource]] over `api`, with an empty metadata cache. */
  def create[I[_]: Sync, F[_]: Sync: Clock](
    api: YahooApi[F],
    metadataTtl: FiniteDuration = DefaultMetadataTtl
  )(implicit logs: Logs[I, F]): I[YahooMarketDataSource[F]] =
    for {
      cache <- Ref.in[I, F, Map[Ticker, (YahooMetadata, Instant)]](Map.empty)
      source <- logs
        .forService[MarketDataSource[F]]
        .map(implicit log => new YahooMarketDataSource[F](api, cache, metadataTtl))
    } yield source
}
