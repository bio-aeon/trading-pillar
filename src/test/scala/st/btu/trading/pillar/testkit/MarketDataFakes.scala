package st.btu.trading.pillar.testkit

import cats.Functor
import cats.effect.{IO, Ref}
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.gateways.*
import st.btu.trading.pillar.gateways.yahoo.{YahooApi, YahooChart, YahooDividend, YahooMetadata}
import st.btu.trading.pillar.models.domain.{AccountId, Currency, Figi}
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.services.{PriceHistorySyncService, SyncReport}
import st.btu.trading.pillar.storages.*
import tofu.Delay

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

object MarketDataFakes {

  // --- Effect plumbing shared by the market-data specs ---

  implicit val delayForIO: Delay[IO] = new Delay[IO] {
    def delay[A](a: => A): IO[A] = IO(a)
  }

  def withLocal(initial: ProcessContext): IO[tofu.WithLocal[IO, ProcessContext]] =
    Ref.of[IO, ProcessContext](initial).map { ref =>
      new tofu.WithLocal[IO, ProcessContext] {
        def functor: Functor[IO] = Functor[IO]

        def context: IO[ProcessContext] = ref.get

        def local[A](fa: IO[A])(project: ProcessContext => ProcessContext): IO[A] =
          ref.get.flatMap { original =>
            ref.set(project(original)) *> fa.guarantee(ref.set(original))
          }
      }
    }

  // --- YahooApi ---

  trait FakeYahooApi extends YahooApi[IO] {
    def dailyChartCalls: IO[List[String]]
    def metadataCalls: IO[List[String]]
    def dividendsCalls: IO[List[String]]
  }

  def yahooApi(
    charts: Map[String, IO[Option[YahooChart]]] = Map.empty,
    metadata: Map[String, IO[YahooMetadata]] = Map.empty,
    dividends: Map[String, IO[List[YahooDividend]]] = Map.empty
  ): IO[FakeYahooApi] = {
    val chartResponses = charts
    val metadataResponses = metadata
    val dividendsResponses = dividends
    for {
      chartCallsRef <- Ref.of[IO, List[String]](List.empty)
      metadataCallsRef <- Ref.of[IO, List[String]](List.empty)
      dividendsCallsRef <- Ref.of[IO, List[String]](List.empty)
    } yield new FakeYahooApi {
      def dailyChart(
        ticker: String,
        since: ZonedDateTime,
        until: ZonedDateTime
      ): IO[Option[YahooChart]] =
        chartCallsRef.update(_ :+ ticker) >> chartResponses.getOrElse(ticker, IO.pure(None))

      def metadata(ticker: String): IO[YahooMetadata] =
        metadataCallsRef.update(_ :+ ticker) >> metadataResponses.getOrElse(
          ticker,
          IO.raiseError(
            new NoSuchElementException(s"no fake Yahoo metadata configured for $ticker")
          )
        )

      def dividends(
        ticker: String,
        since: ZonedDateTime,
        until: ZonedDateTime
      ): IO[List[YahooDividend]] =
        dividendsCallsRef
          .update(_ :+ ticker) >> dividendsResponses.getOrElse(ticker, IO.pure(List.empty))

      def dailyChartCalls: IO[List[String]] = chartCallsRef.get
      def metadataCalls: IO[List[String]] = metadataCallsRef.get
      def dividendsCalls: IO[List[String]] = dividendsCallsRef.get
    }
  }

  // --- MarketDataSource (source-agnostic fake, used by service/process specs) ---

  trait FakeMarketDataSource extends MarketDataSource[IO] {
    def dailyBarsCalls: IO[List[Ticker]]
    def instrumentInfoCalls: IO[List[Ticker]]
    def dividendsCalls: IO[List[Ticker]]
  }

  def marketDataSource(
    src: Source = Source.Yahoo,
    bars: Map[Ticker, IO[List[Bar]]] = Map.empty,
    quotes: Map[Ticker, IO[Option[Quote]]] = Map.empty,
    info: Map[Ticker, IO[Option[InstrumentInfo]]] = Map.empty,
    dividendEvents: Map[Ticker, IO[List[DividendEvent]]] = Map.empty,
    onBoard: Option[ClassCode] = None
  ): IO[FakeMarketDataSource] =
    for {
      barsCallsRef <- Ref.of[IO, List[Ticker]](List.empty)
      infoCallsRef <- Ref.of[IO, List[Ticker]](List.empty)
      dividendsCallsRef <- Ref.of[IO, List[Ticker]](List.empty)
    } yield new FakeMarketDataSource {
      def source: Source = src

      def board: Option[ClassCode] = onBoard

      def dailyBars(ticker: Ticker, since: LocalDate, until: LocalDate): IO[List[Bar]] =
        barsCallsRef.update(_ :+ ticker) >> bars.getOrElse(ticker, IO.pure(List.empty))

      def latestQuote(ticker: Ticker): IO[Option[Quote]] =
        quotes.getOrElse(ticker, IO.pure(None))

      def instrumentInfo(ticker: Ticker): IO[Option[InstrumentInfo]] =
        infoCallsRef.update(_ :+ ticker) >> info.getOrElse(ticker, IO.pure(None))

      def dividends(ticker: Ticker, since: LocalDate, until: LocalDate): IO[List[DividendEvent]] =
        dividendsCallsRef
          .update(_ :+ ticker) >> dividendEvents.getOrElse(ticker, IO.pure(List.empty))

      def dailyBarsCalls: IO[List[Ticker]] = barsCallsRef.get
      def instrumentInfoCalls: IO[List[Ticker]] = infoCallsRef.get
      def dividendsCalls: IO[List[Ticker]] = dividendsCallsRef.get
    }

  // A Tinkoff-flavoured source that only remembers the board it was opened on, for specs that
  // assert which board the router chose.
  final case class BoardView(classCode: Option[ClassCode]) extends MarketDataSource[IO] {
    def source: Source = Source.Tinkoff

    def board: Option[ClassCode] = classCode

    def dailyBars(ticker: Ticker, since: LocalDate, until: LocalDate): IO[List[Bar]] =
      IO.pure(List.empty)

    def latestQuote(ticker: Ticker): IO[Option[Quote]] = IO.pure(None)

    def instrumentInfo(ticker: Ticker): IO[Option[InstrumentInfo]] = IO.pure(None)

    def dividends(ticker: Ticker, since: LocalDate, until: LocalDate): IO[List[DividendEvent]] =
      IO.pure(List.empty)
  }

  val boardViews: TinkoffBoards[IO] = new TinkoffBoards[IO] {
    def onBoard(classCode: Option[ClassCode]): MarketDataSource[IO] = BoardView(classCode)
  }

  // Serves every board with the one given source, for specs that assert what that source was asked.
  def tinkoffBoardsOver(source: MarketDataSource[IO]): TinkoffBoards[IO] =
    new TinkoffBoards[IO] {
      def onBoard(classCode: Option[ClassCode]): MarketDataSource[IO] = source
    }

  def marketDataSources(
    yahoo: Option[MarketDataSource[IO]] = None,
    tinkoff: Option[TinkoffBoards[IO]] = None,
    settings: List[TickerSource] = List.empty,
    default: SourceSetting = SourceSetting.Yahoo
  ): IO[MarketDataSources[IO]] =
    tickerSourceStorage(settings).map(storage =>
      new MarketDataSourcesImpl[IO](yahoo, tinkoff, storage, default)
    )

  trait FakeTickerSourceStorage extends TickerSourceStorage[IO]

  def tickerSourceStorage(initial: List[TickerSource] = List.empty): IO[FakeTickerSourceStorage] =
    Ref.of[IO, Map[Ticker, TickerSource]](initial.map(entry => entry.ticker -> entry).toMap).map {
      ref =>
        new FakeTickerSourceStorage {
          def find(ticker: Ticker): IO[Option[TickerSource]] = ref.get.map(_.get(ticker))

          def all: IO[List[TickerSource]] = ref.get.map(_.values.toList)

          def upsert(entry: TickerSource): IO[Unit] = ref.update(_.updated(entry.ticker, entry))

          def delete(ticker: Ticker): IO[Boolean] =
            ref.modify(entries => (entries - ticker, entries.contains(ticker)))
        }
    }

  // Every call fails with `failure`, for specs of how a caller reacts to an unreadable settings
  // store.
  def failingTickerSourceStorage(failure: Throwable): IO[FakeTickerSourceStorage] =
    IO.pure(new FakeTickerSourceStorage {
      def find(ticker: Ticker): IO[Option[TickerSource]] = IO.raiseError(failure)

      def all: IO[List[TickerSource]] = IO.raiseError(failure)

      def upsert(entry: TickerSource): IO[Unit] = IO.raiseError(failure)

      def delete(ticker: Ticker): IO[Boolean] = IO.raiseError(failure)
    })

  // --- PriceHistoryStorage ---

  trait FakePriceHistoryStorage extends PriceHistoryStorage[IO] {
    def allBars: IO[List[Bar]]
  }

  def priceHistoryStorage(initial: List[Bar] = List.empty): IO[FakePriceHistoryStorage] =
    Ref
      .of[IO, Map[(Ticker, Source), List[Bar]]](initial.groupBy(b => (b.ticker, b.source)))
      .map { ref =>
        new FakePriceHistoryStorage {
          def lastBarDate(ticker: Ticker, source: Source): IO[Option[LocalDate]] =
            ref.get.map(_.getOrElse((ticker, source), Nil).map(_.date).maxOption)

          def bars(ticker: Ticker, source: Source, from: LocalDate, to: LocalDate): IO[List[Bar]] =
            ref.get.map(
              _.getOrElse((ticker, source), Nil)
                .filter(b => !b.date.isBefore(from) && !b.date.isAfter(to))
                .sortBy(_.date.toEpochDay)
            )

          def latestBars(ticker: Ticker, source: Source, limit: Int): IO[List[Bar]] =
            ref.get.map(
              _.getOrElse((ticker, source), Nil).sortBy(_.date.toEpochDay).takeRight(limit)
            )

          def upsertBars(newBars: List[Bar]): IO[Int] =
            ref
              .update { m =>
                newBars.groupBy(b => (b.ticker, b.source)).foldLeft(m) { case (acc, (key, bs)) =>
                  val merged = (acc.getOrElse(key, Nil) ++ bs).map(b => b.date -> b).toMap
                  acc.updated(key, merged.values.toList.sortBy(_.date.toEpochDay))
                }
              }
              .as(newBars.size)

          def allBars: IO[List[Bar]] = ref.get.map(_.values.toList.flatten)
        }
      }

  // --- InstrumentStorage ---

  trait FakeInstrumentStorage extends InstrumentStorage[IO] {
    def all: IO[Map[Ticker, InstrumentInfo]]
  }

  def instrumentStorage(
    initial: Map[Ticker, InstrumentInfo] = Map.empty
  ): IO[FakeInstrumentStorage] =
    Ref.of[IO, Map[Ticker, InstrumentInfo]](initial).map { ref =>
      new FakeInstrumentStorage {
        def find(ticker: Ticker): IO[Option[InstrumentInfo]] = ref.get.map(_.get(ticker))

        def upsert(info: InstrumentInfo): IO[Unit] = ref.update(_.updated(info.ticker, info))

        def all: IO[Map[Ticker, InstrumentInfo]] = ref.get
      }
    }

  // Every call fails with `failure`, for specs of how a caller reacts to an unavailable metadata
  // store.
  def failingInstrumentStorage(failure: Throwable): IO[InstrumentStorage[IO]] =
    IO.pure(new InstrumentStorage[IO] {
      def find(ticker: Ticker): IO[Option[InstrumentInfo]] = IO.raiseError(failure)

      def upsert(info: InstrumentInfo): IO[Unit] = IO.raiseError(failure)
    })

  trait FakeBrokerInstrumentStorage extends BrokerInstrumentStorage[IO] {
    def all: IO[List[BrokerInstrument]]
  }

  def brokerInstrumentStorage(
    initial: List[BrokerInstrument] = List.empty
  ): IO[FakeBrokerInstrumentStorage] =
    Ref.of[IO, Map[Figi, BrokerInstrument]](initial.map(i => i.figi -> i).toMap).map { ref =>
      new FakeBrokerInstrumentStorage {
        def find(figi: Figi): IO[Option[BrokerInstrument]] = ref.get.map(_.get(figi))

        def upsert(instrument: BrokerInstrument): IO[Unit] =
          ref.update(_.updated(instrument.figi, instrument))

        def all: IO[List[BrokerInstrument]] = ref.get.map(_.values.toList)
      }
    }

  // Terse fixture builder: only the fields a spec cares about are named, the rest get arbitrary
  // but valid values.
  def brokerInstrument(
    figi: String,
    ticker: Ticker,
    currency: String = "RUB",
    classCode: String = "TQBR"
  ): BrokerInstrument =
    BrokerInstrument(
      Figi(figi),
      ticker,
      ClassCode(classCode),
      name = ticker.value,
      instrumentType = "share",
      currency = Currency(currency),
      updatedAt = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    )

  def instrumentDirectory(known: List[BrokerInstrument] = List.empty): IO[InstrumentDirectory[IO]] =
    IO.pure(new InstrumentDirectory[IO] {
      def byFigi(figi: Figi): IO[Option[BrokerInstrument]] = IO.pure(known.find(_.figi == figi))
    })

  // --- WatchlistStorage ---

  trait FakeWatchlistStorage extends WatchlistStorage[IO] {
    def items: IO[List[WatchlistItem]]
  }

  def watchlistStorage(initial: List[WatchlistItem] = List.empty): IO[FakeWatchlistStorage] =
    Ref.of[IO, List[WatchlistItem]](initial).map { ref =>
      new FakeWatchlistStorage {
        def allEnabled: IO[List[WatchlistItem]] = ref.get.map(_.filter(_.enabled))

        def byAccount(accountId: AccountId): IO[List[WatchlistItem]] =
          ref.get.map(_.filter(_.accountId == accountId))

        def add(item: WatchlistItem): IO[Unit] = ref.update(_ :+ item)

        def remove(accountId: AccountId, ticker: Ticker): IO[Boolean] =
          ref.modify { current =>
            val (removed, kept) =
              current.partition(i => i.accountId == accountId && i.ticker == ticker)
            (kept, removed.nonEmpty)
          }

        def items: IO[List[WatchlistItem]] = ref.get
      }
    }

  // --- PriceHistorySyncService (fake, used by PriceHistorySyncProcessSpec) ---

  trait FakePriceHistorySyncService extends PriceHistorySyncService[IO] {
    def syncedTickers: IO[List[Ticker]]
  }

  def priceHistorySyncService(
    outcomes: Map[Ticker, IO[SyncReport]] = Map.empty
  ): IO[FakePriceHistorySyncService] =
    Ref.of[IO, List[Ticker]](List.empty).map { callsRef =>
      new FakePriceHistorySyncService {
        def syncSymbol(item: WatchlistItem, today: LocalDate): IO[SyncReport] =
          callsRef.update(_ :+ item.ticker) >> outcomes.getOrElse(
            item.ticker,
            IO.pure(SyncReport(item.ticker, Source.Yahoo, 0, 0, 0, today, today))
          )

        def syncedTickers: IO[List[Ticker]] = callsRef.get
      }
    }

  // --- DividendStorage ---

  trait FakeDividendStorage extends DividendStorage[IO] {
    def all: IO[List[DividendEvent]]
  }

  def dividendStorage(initial: List[DividendEvent] = List.empty): IO[FakeDividendStorage] =
    Ref.of[IO, List[DividendEvent]](initial).map { ref =>
      new FakeDividendStorage {
        def upsert(events: List[DividendEvent]): IO[Int] =
          ref
            .update { current =>
              val merged = (current ++ events).map(e => (e.ticker, e.exDate, e.source) -> e).toMap
              merged.values.toList
            }
            .as(events.size)

        def byTicker(ticker: Ticker, source: Source, from: LocalDate, to: LocalDate)
          : IO[List[DividendEvent]] =
          ref.get.map(
            _.filter(e =>
              e.ticker == ticker && e.source == source && !e.exDate.isBefore(from) &&
                !e.exDate.isAfter(to)
            ).sortBy(_.exDate.toEpochDay)
          )

        def upcoming(tickers: List[SourcedTicker], from: LocalDate): IO[List[DividendEvent]] = {
          val wanted = tickers.map(t => (t.ticker, t.source)).toSet
          ref.get.map(
            _.filter(e => wanted.contains((e.ticker, e.source)) && !e.exDate.isBefore(from))
              .sortBy(_.exDate.toEpochDay)
          )
        }

        def all: IO[List[DividendEvent]] = ref.get
      }
    }
}
