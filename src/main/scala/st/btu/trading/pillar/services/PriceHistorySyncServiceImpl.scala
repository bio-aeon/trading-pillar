package st.btu.trading.pillar.services

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.{Applicative, Functor}
import st.btu.trading.pillar.gateways.{MarketDataSource, MarketDataSources}
import st.btu.trading.pillar.models.market.*
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
    sources.forTicker(item.ticker).attempt.flatMap {
      case Right(source) => syncWithSource(source, item, today)
      case Left(MarketDataError.SourceNotConfigured(source)) =>
        skipUnconfigured(item.ticker, source, today)
      case Left(error) => F.raiseError(error)
    }

  private def skipUnconfigured(ticker: Ticker, source: Source, today: LocalDate): F[SyncReport] =
    warn"No market data source configured for ${source.value}; skipping ${ticker.value}"
      .as(SyncReport(ticker, source, 0, 0, 0, today, today))

  private def syncWithSource(
    source: MarketDataSource[F],
    item: WatchlistItem,
    today: LocalDate
  ): F[SyncReport] =
    for {
      stored_? <- storedMetadata(item.ticker)
      lastBarDate_? <-
        if (stored_?.exists(originChanged(_, source))) F.pure(Option.empty[LocalDate])
        else priceHistory.lastBarDate(item.ticker, source.origin.source)
      since = lastBarDate_?
        .map(_.minusDays(config.overlapDays.toLong))
        .getOrElse(today.minusYears(config.backfillYears.toLong))
      report <-
        if (since.isAfter(today))
          F.pure(SyncReport(item.ticker, source.origin.source, 0, 0, 0, since, today))
        else syncBars(source, item.ticker, since, today)
      _ <- refreshMetadataIfStale(source, item.ticker, stored_?)
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
    } yield SyncReport(
      ticker,
      source.origin.source,
      bars.size,
      upserted,
      dividendCount,
      since,
      until
    )

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

  // An unreadable record counts as absent, so a metadata failure cannot fail the bar sync.
  private def storedMetadata(ticker: Ticker): F[Option[InstrumentInfo]] =
    F.handleErrorWith(instruments.find(ticker))(e =>
      warnCause"Failed to read instrument metadata for ${ticker.value}" (e).as(None)
    )

  // Metadata failure must not fail the bar sync: log a warning and move on.
  private def refreshMetadataIfStale(
    source: MarketDataSource[F],
    ticker: Ticker,
    stored: Option[InstrumentInfo]
  ): F[Unit] =
    F.handleErrorWith(
      nowF.flatMap(now =>
        Applicative[F].whenA(stored.forall(stale(_, source, now)))(refreshMetadata(source, ticker))
      )
    )(e => warnCause"Failed to refresh instrument metadata for ${ticker.value}" (e))

  // Metadata read from another source, board or Yahoo symbol describes the ticker as that one does,
  // so a change of origin refreshes it at once, whatever its age.
  private def stale(
    info: InstrumentInfo,
    source: MarketDataSource[F],
    now: ZonedDateTime
  ): Boolean =
    originChanged(info, source) ||
      info.updatedAt.isBefore(now.minusNanos(config.metadataRefresh.toNanos))

  // Bars are stored per source, not per board or symbol, so those stored under another origin may
  // have been read from another board or symbol: a changed origin refetches the whole window to
  // replace them.
  private def originChanged(info: InstrumentInfo, source: MarketDataSource[F]): Boolean =
    info.origin != source.origin

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
