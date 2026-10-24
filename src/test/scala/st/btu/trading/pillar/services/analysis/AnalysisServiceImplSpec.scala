package st.btu.trading.pillar.services.analysis

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.storages.{DividendStorage, PriceHistoryStorage}
import st.btu.trading.pillar.testkit.MarketDataFakes

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

class AnalysisServiceImplSpec extends Specification with CatsEffect {

  private val ticker = Ticker("AAPL")
  private val today = LocalDate.now(ZoneOffset.UTC)
  private val asOf = ZonedDateTime.now(ZoneOffset.UTC)
  private val usd = Currency("USD")

  // High/low = close (zero intrabar spread): none of the assertions below depend on the spread,
  // only on the close series itself.
  private def bar(date: LocalDate, close: BigDecimal): Bar =
    Bar(ticker, date, close, close, close, close, close, 0L, Source.Yahoo)

  private def quote(price: BigDecimal, currency: Currency = usd): Quote =
    Quote(ticker, price, currency, asOf, Source.Yahoo)

  private def info(instrumentType: String = "EQUITY", exchange: String = "NASDAQ"): InstrumentInfo =
    InstrumentInfo(
      ticker,
      SourceSetting.Yahoo(None),
      Some("Apple"),
      instrumentType,
      usd,
      exchange,
      "America/New_York",
      None,
      asOf
    )

  private def div(
    exDate: LocalDate,
    amount: BigDecimal,
    source: Source = Source.Yahoo
  ): DividendEvent =
    DividendEvent(ticker, exDate, amount, Some(usd), source)

  // Raises on any call - used to prove the NotFound short-circuit never reaches storage.
  private val raisingPriceHistory: PriceHistoryStorage[IO] = new PriceHistoryStorage[IO] {
    def lastBarDate(t: Ticker, s: Source): IO[Option[LocalDate]] = boom
    def bars(t: Ticker, s: Source, from: LocalDate, to: LocalDate): IO[List[Bar]] = boom
    def latestBars(t: Ticker, s: Source, limit: Int): IO[List[Bar]] = boom
    def upsertBars(bs: List[Bar]): IO[Int] = boom
    private def boom[A]: IO[A] =
      IO.raiseError(new RuntimeException("PriceHistoryStorage should not be called"))
  }

  private val raisingDividends: DividendStorage[IO] = new DividendStorage[IO] {
    def upsert(events: List[DividendEvent]): IO[Int] = boom
    def byTicker(t: Ticker, s: Source, from: LocalDate, to: LocalDate): IO[List[DividendEvent]] =
      boom
    def upcoming(tickers: List[SourcedTicker], from: LocalDate): IO[List[DividendEvent]] = boom
    private def boom[A]: IO[A] =
      IO.raiseError(new RuntimeException("DividendStorage should not be called"))
  }

  // Yahoo and Tinkoff adapters serve the same quotes and metadata, so which one answers is decided
  // by `settings` alone; a ticker without a setting uses Yahoo.
  private def service(
    quotes: Map[Ticker, IO[Option[Quote]]] = Map.empty,
    infos: Map[Ticker, IO[Option[InstrumentInfo]]] = Map.empty,
    bars: List[Bar] = List.empty,
    dividends: List[DividendEvent] = List.empty,
    priceHistory: Option[PriceHistoryStorage[IO]] = None,
    dividendStorage: Option[DividendStorage[IO]] = None,
    settings: List[TickerSource] = List.empty
  ): IO[AnalysisService[IO]] =
    for {
      yahoo <- MarketDataFakes.marketDataSource(quotes = quotes, info = infos)
      tinkoff <- MarketDataFakes.marketDataSource(
        setting = SourceSetting.Tinkoff(None),
        quotes = quotes,
        info = infos
      )
      ph <- priceHistory.fold[IO[PriceHistoryStorage[IO]]](
        MarketDataFakes.priceHistoryStorage(bars)
      )(IO.pure)
      ds <- dividendStorage.fold[IO[DividendStorage[IO]]](
        MarketDataFakes.dividendStorage(dividends)
      )(IO.pure)
      sources <- MarketDataFakes.marketDataSources(
        yahoo = Some(MarketDataFakes.yahooSymbolsOver(yahoo)),
        tinkoff = Some(MarketDataFakes.tinkoffBoardsOver(tinkoff)),
        settings = settings
      )
    } yield AnalysisServiceImpl.create[IO](sources, ph, ds)

  "AnalysisServiceImpl" >> {
    "analyze" >> {

      "returns an empty analysis and never touches stored bars or dividends when the ticker isn't found" >> {
        for {
          svc <- service(
            quotes = Map(ticker -> IO.raiseError(MarketDataError.NotFound(ticker))),
            priceHistory = Some(raisingPriceHistory),
            dividendStorage = Some(raisingDividends)
          )
          result <- svc.analyze(ticker)
        } yield result must_== StockAnalysis.empty(ticker)
      }

      "propagates a market data error that isn't a not-found" >> {
        for {
          svc <- service(quotes = Map(ticker -> IO.raiseError(MarketDataError.RateLimited(None))))
          result <- svc.analyze(ticker).attempt
        } yield result must beLeft(MarketDataError.RateLimited(None))
      }

      "passes through price, currency and asOf from the quote, and type and exchange from instrument info" >> {
        for {
          svc <- service(
            quotes = Map(ticker -> IO.pure(Some(quote(BigDecimal(123.45))))),
            infos = Map(ticker -> IO.pure(Some(info())))
          )
          result <- svc.analyze(ticker)
        } yield {
          result.price must beSome(BigDecimal(123.45))
          result.currency must beSome(usd)
          result.asOf must beSome(asOf)
          result.instrumentType must beSome("EQUITY")
          result.exchange must beSome("NASDAQ")
        }
      }

      "computes returns from the close nearest on or before the target date, ignoring a later bar" >> {
        // today-25 is more recent than the 1m (30-day) cutoff and must be ignored in its favour;
        // today-35 is the nearest bar on or before the cutoff and is the correct base.
        val bars = List(
          bar(today, BigDecimal(200)), // latest
          bar(today.minusDays(25), BigDecimal(300)), // after the 1m cutoff - must be ignored
          bar(today.minusDays(35), BigDecimal(100)) // on/before the 1m cutoff - correct base
        )
        for {
          svc <- service(
            quotes = Map(ticker -> IO.pure(Some(quote(BigDecimal(200))))),
            infos = Map(ticker -> IO.pure(Some(info()))),
            bars = bars
          )
          result <- svc.analyze(ticker)
        } yield {
          result.return1m must beSome(BigDecimal(100))
          // No bar is old enough to be on or before the 3m/1y cutoffs.
          result.return3m must beNone
          result.return1y must beNone
        }
      }

      "computes SMA, RSI, above-SMA and the 52-week high/low distance from the stored bars" >> {
        // 250 daily bars, close(i) = 100 + i for i = 0 (oldest, today-249) .. 249 (today) - a clean
        // arithmetic progression so SMA has an exact closed form and every change is a gain (RSI is
        // exactly 100). Monotonically increasing, so the latest close is also the window's high.
        val n = 250
        val bars =
          (0 until n).map(i => bar(today.minusDays((n - 1 - i).toLong), BigDecimal(100 + i))).toList
        for {
          svc <- service(
            quotes = Map(ticker -> IO.pure(Some(quote(BigDecimal(100 + n - 1))))),
            infos = Map(ticker -> IO.pure(Some(info()))),
            bars = bars
          )
          result <- svc.analyze(ticker)
        } yield {
          result.sma50 must beSome(BigDecimal("324.5")) // avg of closes 300..349
          result.sma200 must beSome(BigDecimal("249.5")) // avg of closes 150..349
          result.aboveSma50 must beSome(true)
          result.aboveSma200 must beSome(true)
          result.rsi14 must beSome(BigDecimal(100))
          result.high52w must beSome(BigDecimal(349))
          result.low52w must beSome(BigDecimal(100))
          result.distanceToHigh52wPct must beSome(BigDecimal(0))
          result.distanceToLow52wPct must beSome(BigDecimal(249))
        }
      }

      "computes trailing-12-month dividends and the dividend yield from the quote price" >> {
        val events = List(
          div(today.minusMonths(6), BigDecimal(2)),
          div(today.minusMonths(3), BigDecimal(2)),
          div(today.minusYears(2), BigDecimal(99)) // outside the trailing 12 months
        )
        for {
          svc <- service(
            quotes = Map(ticker -> IO.pure(Some(quote(BigDecimal(100))))),
            infos = Map(ticker -> IO.pure(Some(info()))),
            dividends = events
          )
          result <- svc.analyze(ticker)
        } yield {
          result.dividends12m must_== BigDecimal(4)
          result.dividendYieldPct must beSome(BigDecimal(4))
        }
      }

      "counts only the dividends of the source the ticker is configured to use" >> {
        val events = List(
          div(today.minusMonths(6), BigDecimal(2), Source.Yahoo),
          div(today.minusMonths(3), BigDecimal(50), Source.Tinkoff)
        )
        for {
          svc <- service(
            quotes = Map(ticker -> IO.pure(Some(quote(BigDecimal(100))))),
            infos = Map(ticker -> IO.pure(Some(info()))),
            dividends = events,
            settings = List(TickerSource(ticker, SourceSetting.Tinkoff(None)))
          )
          result <- svc.analyze(ticker)
        } yield result.dividends12m must_== BigDecimal(50)
      }
    }
  }
}
