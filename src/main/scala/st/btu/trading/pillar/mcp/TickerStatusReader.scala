package st.btu.trading.pillar.mcp

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.traverse.*
import st.btu.trading.pillar.models.market.{MarketDataError, Source, SourceSetting, Ticker}

// Gathers what the bot knows about a ticker from the stores and the router, for the report.
private[mcp] final class TickerStatusReader[F[_]: Clock](ports: McpPorts[F])(implicit
  F: MonadThrow[F]
) {

  def read(ticker: Ticker): F[TickerStatus] =
    for {
      storedSetting <- ports.tickerSources.find(ticker)
      setting <- ports.marketDataSources.settingFor(ticker)
      adapter <- adapterOrigin(ticker)
      entries <- ports.watchlist.byAccount(ports.accountId)
      lastBars <- TickerStatus.BarSources.traverse(lastBar(ticker, _))
      metadata <- ports.instruments.find(ticker)
      now <- Clock[F].realTimeInstant
    } yield TickerStatus(
      ticker,
      storedSetting,
      setting,
      adapter,
      entries.find(_.ticker == ticker),
      lastBars,
      metadata,
      now
    )

  // None when the setting's source has no adapter wired.
  private def adapterOrigin(ticker: Ticker): F[Option[SourceSetting]] =
    ports.marketDataSources
      .forTicker(ticker)
      .map(_.origin.some)
      .recover { case MarketDataError.SourceNotConfigured(_) => None }

  private def lastBar(ticker: Ticker, source: Source): F[LastBar] =
    ports.priceHistory.lastBarDate(ticker, source).map(LastBar(source, _))
}
