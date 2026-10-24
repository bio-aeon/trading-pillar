package st.btu.trading.pillar.services.analysis

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.applicativeError.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.{MarketDataSource, MarketDataSources}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.{DividendStorage, PriceHistoryStorage}
import st.btu.trading.pillar.strategy.BarHistory
import st.btu.trading.pillar.strategy.indicators.Indicators

import java.time.{LocalDate, ZoneOffset}

// The quote/instrument-info lookup is the only step that can raise MarketDataError.NotFound; when
// it does, `analyze` short-circuits to StockAnalysis.empty rather than touching the stored bars or
// dividends. Every other error propagates.
final class AnalysisServiceImpl[F[_]] private (
  marketDataSources: MarketDataSources[F],
  priceHistory: PriceHistoryStorage[F],
  dividends: DividendStorage[F]
)(implicit F: MonadThrow[F], clock: Clock[F])
    extends AnalysisService[F] {

  import AnalysisServiceImpl.*

  def analyze(ticker: Ticker): F[StockAnalysis] =
    for {
      source <- marketDataSources.forTicker(ticker)
      found <- fetchQuoteAndInfo(source, ticker)
      result <- found match {
        case None => F.pure(StockAnalysis.empty(ticker))
        case Some((quote, info)) => buildAnalysis(source.origin.source, ticker, quote, info)
      }
    } yield result

  private def fetchQuoteAndInfo(
    source: MarketDataSource[F],
    ticker: Ticker
  ): F[Option[(Option[Quote], Option[InstrumentInfo])]] =
    (for {
      quote <- source.latestQuote(ticker)
      info <- source.instrumentInfo(ticker)
    } yield Option((quote, info))).recover { case MarketDataError.NotFound(_) => None }

  private def buildAnalysis(
    source: Source,
    ticker: Ticker,
    quote: Option[Quote],
    info: Option[InstrumentInfo]
  ): F[StockAnalysis] =
    for {
      today <- todayUtc
      bars <- priceHistory.bars(ticker, source, today.minusDays(LookbackDays.toLong), today)
      events <- dividends.byTicker(ticker, source, today.minusYears(1), today)
    } yield fromData(ticker, today, quote, info, bars.sortBy(_.date.toEpochDay).toVector, events)

  private def fromData(
    ticker: Ticker,
    today: LocalDate,
    quote: Option[Quote],
    info: Option[InstrumentInfo],
    bars: Vector[Bar],
    events: List[DividendEvent]
  ): StockAnalysis = {
    val history = BarHistory(bars)
    val latestClose = history.last.map(_.close)
    val price = quote.map(_.price)
    val dividends12m = events.map(_.amount).sum
    val sma50 = history.sma(50)
    val sma200 = history.sma(200)
    val windowStart = today.minusWeeks(WindowWeeks.toLong)
    val window = bars.filter(b => !b.date.isBefore(windowStart))
    val high52w = window.map(_.high).maxOption
    val low52w = window.map(_.low).minOption

    StockAnalysis(
      ticker = ticker,
      price = price,
      currency = quote.map(_.currency).orElse(info.map(_.currency)),
      asOf = quote.map(_.asOf),
      instrumentType = info.map(_.instrumentType),
      exchange = info.map(_.exchange),
      return1m = returnOver(bars, today, OneMonthDays, latestClose),
      return3m = returnOver(bars, today, ThreeMonthsDays, latestClose),
      return1y = returnOver(bars, today, OneYearDays, latestClose),
      sma50 = sma50,
      sma200 = sma200,
      aboveSma50 = above(latestClose, sma50),
      aboveSma200 = above(latestClose, sma200),
      rsi14 = history.rsi(14),
      atr14Pct = for {
        atr <- history.atr(14)
        close <- latestClose
        pct <- pctOf(atr, Some(close))
      } yield pct,
      high52w = high52w,
      low52w = low52w,
      distanceToHigh52wPct = for {
        close <- latestClose
        high <- high52w
        pct <- pctOf(close - high, Some(high))
      } yield pct,
      distanceToLow52wPct = for {
        close <- latestClose
        low <- low52w
        pct <- pctOf(close - low, Some(low))
      } yield pct,
      dividends12m = dividends12m,
      dividendYieldPct = pctOf(dividends12m, price)
    )
  }

  // The close of the bar nearest on or before `today - daysAgo`, versus the series' latest close.
  private def returnOver(
    bars: Vector[Bar],
    today: LocalDate,
    daysAgo: Int,
    latestClose: Option[BigDecimal]
  ): Option[BigDecimal] = {
    val target = today.minusDays(daysAgo.toLong)
    for {
      base <- bars.filter(!_.date.isAfter(target)).lastOption.map(_.close)
      last <- latestClose
      pct <- pctOf(last - base, Some(base))
    } yield pct
  }

  private def above(close: Option[BigDecimal], sma: Option[BigDecimal]): Option[Boolean] =
    for {
      c <- close
      s <- sma
    } yield c > s

  private def pctOf(numerator: BigDecimal, denominator: Option[BigDecimal]): Option[BigDecimal] =
    denominator.filter(_ != BigDecimal(0)).map(d => Indicators.divide(numerator, d) * 100)

  private def todayUtc: F[LocalDate] =
    clock.realTimeInstant.map(LocalDate.ofInstant(_, ZoneOffset.UTC))
}

object AnalysisServiceImpl {

  private val LookbackDays = 400

  private val WindowWeeks = 52
  private val OneMonthDays = 30
  private val ThreeMonthsDays = 91
  private val OneYearDays = 365

  def create[F[_]: MonadThrow: Clock](
    marketDataSources: MarketDataSources[F],
    priceHistory: PriceHistoryStorage[F],
    dividends: DividendStorage[F]
  ): AnalysisService[F] =
    new AnalysisServiceImpl[F](marketDataSources, priceHistory, dividends)
}
