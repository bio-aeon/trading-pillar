package st.btu.trading.pillar.gateways.tcs

import cats.effect.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import com.google.protobuf.timestamp.Timestamp
import ru.tinkoff.piapi.contract.v1.common.{MoneyValue, Quotation}
import ru.tinkoff.piapi.contract.v1.instruments.*
import ru.tinkoff.piapi.contract.v1.marketdata.*
import st.btu.trading.pillar.facades.TcsGrpc
import st.btu.trading.pillar.gateways.{MarketDataSource, TinkoffBoards}
import st.btu.trading.pillar.gateways.tcs.TcsMarketDataSource.{InstrumentKey, TcsMarketDataConfig}
import st.btu.trading.pillar.models.domain.Currency
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.tcs
import tofu.lift.Lift
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{Clock as _, *}
import scala.annotation.tailrec

/** Market data from Tinkoff Invest over gRPC, with tickers looked up on the configured default
  * board. [[onBoard]] gives views on other boards, which share the instruments resolved so far.
  */
final class TcsMarketDataSource[F[_]: Logging] private (
  instruments: InstrumentsServiceFs2Grpc[F, Unit],
  marketData: MarketDataServiceFs2Grpc[F, Unit],
  config: TcsMarketDataConfig,
  cache: Ref[F, Map[InstrumentKey, Instrument]],
  lookupBoard: ClassCode
)(implicit F: Sync[F], clock: Clock[F])
    extends MarketDataSource[F]
    with TinkoffBoards[F] {

  def origin: SourceSetting = SourceSetting.Tinkoff(Some(lookupBoard))

  def onBoard(classCode: Option[ClassCode]): MarketDataSource[F] =
    new TcsMarketDataSource[F](
      instruments,
      marketData,
      config,
      cache,
      classCode.getOrElse(config.defaultClassCode)
    )

  def dailyBars(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[Bar]] =
    for {
      instrument <- resolveInstrument(ticker)
      chunked <- chunkRange(since, until).traverse(fetchChunk(ticker, instrument.figi, _))
    } yield chunked.flatten.sortBy(_.date.toEpochDay)

  def latestQuote(ticker: Ticker): F[Option[Quote]] =
    for {
      instrument <- resolveInstrument(ticker)
      resp <- F.adaptError(
        marketData.getLastPrices(GetLastPricesRequest(instrumentId = Seq(instrument.figi)), ())
      )(mapStatus(ticker))
      now <- nowF
    } yield resp.lastPrices.headOption.map { lastPrice =>
      Quote(
        ticker,
        toScale(lastPrice.price),
        Currency(instrument.currency),
        lastPrice.time.fold(now)(toZonedDateTime),
        Source.Tinkoff
      )
    }

  def instrumentInfo(ticker: Ticker): F[Option[InstrumentInfo]] =
    for {
      instrument <- resolveInstrument(ticker)
      now <- nowF
    } yield Some(
      InstrumentInfo(
        ticker,
        origin,
        Some(instrument.name),
        instrument.instrumentType,
        Currency(instrument.currency),
        instrument.exchange,
        config.zone.getId,
        Some(instrument.figi),
        now
      )
    )

  // Tinkoff's Dividend has no explicit ex-dividend date field, so exDate is approximated as
  // last_buy_date + 1 day (the exchange convention: the ex-date is the trading day right after the
  // last day a purchase still settles in time to land on the dividend register) - see
  // docs/market-data.md. Entries without a last_buy_date, or whose dividend_net is zero, are
  // skipped rather than guessed at.
  def dividends(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[DividendEvent]] =
    for {
      instrument <- resolveInstrument(ticker)
      resp <- F.adaptError(
        instruments.getDividends(
          GetDividendsRequest(
            figi = instrument.figi,
            from = Some(toTimestamp(since.atStartOfDay(config.zone))),
            to = Some(toTimestamp(until.plusDays(1).atStartOfDay(config.zone)))
          ),
          ()
        )
      )(mapStatus(ticker))
    } yield resp.dividends.flatMap(toDividendEvent(ticker, _)).toList.sortBy(_.exDate.toEpochDay)

  private def toDividendEvent(ticker: Ticker, d: Dividend): Option[DividendEvent] =
    d.lastBuyDate.map(t => toZonedDateTime(t).toLocalDate.plusDays(1)).flatMap { exDate =>
      val amount = moneyToBigDecimal(d.dividendNet)
      Option.when(amount != 0)(
        DividendEvent(
          ticker,
          exDate,
          amount,
          d.dividendNet.map(net => Currency(net.currency)),
          Source.Tinkoff
        )
      )
    }

  private def moneyToBigDecimal(m: Option[MoneyValue]): BigDecimal = {
    val money = m.getOrElse(MoneyValue.defaultInstance)
    Prices.fromUnitsNano(money.units, money.nano)
  }

  private def fetchChunk(
    ticker: Ticker,
    figi: String,
    range: (LocalDate, LocalDate)
  ): F[List[Bar]] = {
    val (chunkSince, chunkUntil) = range
    val request = GetCandlesRequest(
      from = Some(toTimestamp(chunkSince.atStartOfDay(config.zone))),
      to = Some(toTimestamp(chunkUntil.plusDays(1).atStartOfDay(config.zone))),
      interval = CandleInterval.CANDLE_INTERVAL_DAY,
      instrumentId = Some(figi)
    )
    F.adaptError(marketData.getCandles(request, ()))(mapStatus(ticker)).flatMap { resp =>
      val (bars, droppedCount) =
        resp.candles.filter(_.isComplete).foldLeft((List.empty[Bar], 0)) {
          case ((acc, dropped), candle) =>
            toBar(ticker, candle).fold((acc, dropped + 1))(bar => (bar :: acc, dropped))
        }
      F.whenA(droppedCount > 0)(
        warn"Dropped $droppedCount timestamp-less Tinkoff candle(s) for ${ticker.value}"
      ) *> F.pure(bars.reverse)
    }
  }

  // None when the candle carries no timestamp - too little to place it on the bar series.
  private def toBar(ticker: Ticker, candle: HistoricCandle): Option[Bar] =
    candle.time.map { t =>
      val close = toScale(candle.close)
      Bar(
        ticker,
        toZonedDateTime(t).toLocalDate,
        toScale(candle.open),
        toScale(candle.high),
        toScale(candle.low),
        close,
        close, // adjClose = close: Tinkoff candles are not split/dividend-adjusted separately.
        candle.volume,
        Source.Tinkoff
      )
    }

  private def resolveInstrument(ticker: Ticker): F[Instrument] =
    cache.get.map(_.get(InstrumentKey(ticker, lookupBoard))).flatMap {
      case Some(instrument) => F.pure(instrument)
      case None => fetchInstrument(ticker)
    }

  private def fetchInstrument(ticker: Ticker): F[Instrument] =
    F.adaptError(
      instruments.getInstrumentBy(
        InstrumentRequest(
          InstrumentIdType.INSTRUMENT_ID_TYPE_TICKER,
          lookupBoard.value,
          ticker.value
        ),
        ()
      )
    )(mapStatus(ticker))
      .flatMap {
        _.instrument match {
          case Some(instrument) =>
            cache.update(_.updated(InstrumentKey(ticker, lookupBoard), instrument)).as(instrument)
          case None => F.raiseError[Instrument](MarketDataError.NotFound(ticker))
        }
      }

  // At most one year (12 calendar months) per GetCandles request.
  private def chunkRange(since: LocalDate, until: LocalDate): List[(LocalDate, LocalDate)] = {
    @tailrec
    def loop(start: LocalDate, acc: List[(LocalDate, LocalDate)]): List[(LocalDate, LocalDate)] =
      if (start.isAfter(until)) acc.reverse
      else {
        val yearEnd = start.plusYears(1).minusDays(1)
        val chunkEnd = if (yearEnd.isAfter(until)) until else yearEnd
        loop(chunkEnd.plusDays(1), (start, chunkEnd) :: acc)
      }

    if (since.isAfter(until)) List.empty else loop(since, List.empty)
  }

  private def toTimestamp(dt: ZonedDateTime): Timestamp = {
    val i = dt.toInstant
    Timestamp.of(i.getEpochSecond, i.getNano)
  }

  private def toZonedDateTime(ts: Timestamp): ZonedDateTime =
    Instant.ofEpochSecond(ts.seconds, ts.nanos.toLong).atZone(config.zone)

  private def toScale(q: Option[Quotation]): BigDecimal =
    quotationToBigDecimal(q.getOrElse(Quotation.defaultInstance))

  private def quotationToBigDecimal(q: Quotation): BigDecimal =
    Prices.fromUnitsNano(q.units, q.nano)

  private def mapStatus(ticker: Ticker): PartialFunction[Throwable, Throwable] =
    TcsErrors.toMarketDataError(Some(ticker))

  private def nowF: F[ZonedDateTime] =
    clock.realTimeInstant.map(ZonedDateTime.ofInstant(_, ZoneOffset.UTC))
}

object TcsMarketDataSource {

  // A ticker as listed on one board, the key its resolved instrument is cached under: the same
  // ticker has a different instrument on each board.
  private[tcs] final case class InstrumentKey(ticker: Ticker, board: ClassCode)

  /** Settings of the Tinkoff market data source.
    *
    * @param defaultClassCode
    *   the board a ticker is looked up on when its setting names none
    * @param zone
    *   the exchange time zone that bar dates and timestamps are expressed in
    */
  final case class TcsMarketDataConfig(
    defaultClassCode: ClassCode = TcsMarketDataConfig.DefaultClassCode,
    zone: ZoneId = TcsMarketDataConfig.DefaultZone
  )

  object TcsMarketDataConfig {

    /** The class code of the Moscow Exchange's main equity board. */
    val DefaultClassCode: ClassCode = ClassCode("TQBR")

    /** The time zone of the Moscow Exchange. */
    val DefaultZone: ZoneId = ZoneId.of("Europe/Moscow")
  }

  /** Wires a [[MarketDataSource]] over the given Tinkoff gRPC stubs, on the configured default
    * board, with an empty ticker-to-instrument cache.
    */
  def create[I[_]: Sync, F[_]: Sync: Clock](
    instruments: InstrumentsServiceFs2Grpc[F, Unit],
    marketData: MarketDataServiceFs2Grpc[F, Unit],
    config: TcsMarketDataConfig
  )(implicit logs: Logs[I, F]): I[TcsMarketDataSource[F]] =
    for {
      cache <- Ref.in[I, F, Map[InstrumentKey, Instrument]](Map.empty)
      source <- logs
        .forService[MarketDataSource[F]]
        .map(implicit log =>
          new TcsMarketDataSource[F](
            instruments,
            marketData,
            config,
            cache,
            config.defaultClassCode
          )
        )
    } yield source

  /** Wires a [[MarketDataSource]] authenticated as `token`, holding its gRPC channel and stubs open
    * for the resource's lifetime.
    */
  def resource[I[_]: Sync, F[_]: Async](token: tcs.Token, config: TcsMarketDataConfig)(implicit
    logs: Logs[I, F],
    liftFI: Lift[F, I]
  ): Resource[I, TcsMarketDataSource[F]] = {
    val metadata = TcsGrpc.bearerMetadata(token)

    for {
      channel <- TcsGrpc.channel[I]
      instrumentsStub <- InstrumentsServiceFs2Grpc
        .clientResource[F, Unit](channel, (_: Unit) => metadata)
        .mapK(liftFI.liftF)
      marketDataStub <- MarketDataServiceFs2Grpc
        .clientResource[F, Unit](channel, (_: Unit) => metadata)
        .mapK(liftFI.liftF)
      source <- Resource.eval(create[I, F](instrumentsStub, marketDataStub, config))
    } yield source
  }
}
