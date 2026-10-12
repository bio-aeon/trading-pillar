package st.btu.trading.pillar.services

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.{Applicative, Functor}
import st.btu.trading.pillar.gateways.{MarketDataSource, MarketDataSources}
import st.btu.trading.pillar.models.market.{InstrumentInfo, Ticker, WatchlistItem}
import st.btu.trading.pillar.storages.{DividendStorage, InstrumentStorage, PriceHistoryStorage}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{LocalDate, ZonedDateTime}

final class PriceHistorySyncServiceImpl[F[_]: Logging] private (
  sources: MarketDataSources[F],
  priceHistory: PriceHistoryStorage[F],
  instruments: InstrumentStorage[F],
  dividends: DividendStorage[F],
  config: PriceHistorySyncConfig
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends PriceHistorySyncService[F] {

  def syncSymbol(item: WatchlistItem, today: LocalDate): F[SyncReport] =
    sources.forSource(item.source) match {
      case None =>
        warn"No market data source configured for ${item.source.value}; skipping ${item.ticker.value}"
          .as(SyncReport(item.ticker, item.source, 0, 0, 0, today, today))
      case Some(source) => syncWithSource(source, item, today)
    }

  private def syncWithSource(
    source: MarketDataSource[F],
    item: WatchlistItem,
    today: LocalDate
  ): F[SyncReport] =
    for {
      lastBarDate_? <- priceHistory.lastBarDate(item.ticker, source.source)
      since = lastBarDate_?
        .map(_.minusDays(config.overlapDays.toLong))
        .getOrElse(today.minusYears(config.backfillYears.toLong))
      report <-
        if (since.isAfter(today))
          F.pure(SyncReport(item.ticker, source.source, 0, 0, 0, since, today))
        else syncBars(source, item.ticker, since, today)
      _ <- refreshMetadataIfStale(source, item.ticker)
    } yield report

  private def syncBars(
    source: MarketDataSource[F],
    ticker: Ticker,
    since: LocalDate,
    until: LocalDate
  ): F[SyncReport] =
    for {
      bars <- source.dailyBars(ticker, since, until)
      upserted <- priceHistory.upsertBars(bars)
      dividendCount <- syncDividends(source, ticker, since, until)
    } yield SyncReport(ticker, source.source, bars.size, upserted, dividendCount, since, until)

  // Same window as the bars just synced. A dividends failure must not fail the bar sync (which
  // already succeeded by this point): log a warning and report zero dividends synced.
  private def syncDividends(
    source: MarketDataSource[F],
    ticker: Ticker,
    since: LocalDate,
    until: LocalDate
  ): F[Int] =
    F.handleErrorWith(source.dividends(ticker, since, until).flatMap(dividends.upsert))(e =>
      warnCause"Failed to sync dividends for ${ticker.value}" (e).as(0)
    )

  // Metadata failure must not fail the bar sync: log a warning and move on.
  private def refreshMetadataIfStale(source: MarketDataSource[F], ticker: Ticker): F[Unit] =
    F.handleErrorWith(
      isMetadataStale(ticker) >>= (Applicative[F].whenA(_)(refreshMetadata(source, ticker)))
    )(e => warnCause"Failed to refresh instrument metadata for ${ticker.value}" (e))

  private def isMetadataStale(ticker: Ticker): F[Boolean] =
    (instruments.find(ticker), nowF).mapN((existing, now) => existing.forall(stale(_, now)))

  private def stale(info: InstrumentInfo, now: ZonedDateTime): Boolean =
    info.updatedAt.isBefore(now.minusNanos(config.metadataRefresh.toNanos))

  private def refreshMetadata(source: MarketDataSource[F], ticker: Ticker): F[Unit] =
    source.instrumentInfo(ticker).flatMap {
      case Some(info) => instruments.upsert(info)
      case None => F.unit
    }

  private def nowF: F[ZonedDateTime] =
    clock.realTimeInstant.map(ZonedDateTime.ofInstant(_, java.time.ZoneOffset.UTC))
}

object PriceHistorySyncServiceImpl {

  /** Wires a [[PriceHistorySyncService]] backed by `sources` and the given storages. */
  def create[I[_]: Functor, F[_]: MonadThrow: Clock](
    sources: MarketDataSources[F],
    priceHistory: PriceHistoryStorage[F],
    instruments: InstrumentStorage[F],
    dividends: DividendStorage[F],
    config: PriceHistorySyncConfig
  )(implicit logs: Logs[I, F]): I[PriceHistorySyncService[F]] =
    logs
      .forService[PriceHistorySyncService[F]]
      .map(implicit log =>
        new PriceHistorySyncServiceImpl[F](sources, priceHistory, instruments, dividends, config)
      )
}
