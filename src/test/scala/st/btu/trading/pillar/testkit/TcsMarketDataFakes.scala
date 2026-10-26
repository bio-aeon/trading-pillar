package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import ru.tinkoff.piapi.contract.v1.instruments.*
import ru.tinkoff.piapi.contract.v1.marketdata.*
import st.btu.trading.pillar.models.market.{ClassCode, Ticker}

// Fakes implementing the generated Fs2Grpc service traits directly (ctx type Unit, matching
// TcsMarketDataSource.create), so TcsMarketDataSourceSpec never touches a real gRPC channel.
object TcsMarketDataFakes {

  private def unimplemented[A](name: String): IO[A] =
    IO.raiseError(new NotImplementedError(s"$name is not used by the TcsMarketDataSource fakes"))

  // --- InstrumentsServiceFs2Grpc (only getInstrumentBy / getDividends are exercised by
  // TcsMarketDataSource) ---

  trait FakeInstrumentsService extends InstrumentsServiceFs2Grpc[IO, Unit] {
    def getInstrumentByCalls: IO[List[InstrumentRequest]]
    def getDividendsCalls: IO[List[GetDividendsRequest]]
  }

  // A ticker as looked up on one board; the key of byBoard.
  final case class OnBoard(classCode: ClassCode, ticker: Ticker)

  // A ticker lookup is answered from byBoard, keyed by the request's class code and ticker, and
  // otherwise from byTicker, keyed by the ticker alone; a FIGI lookup is answered from byFigi.
  // Dividends are keyed by GetDividendsRequest.instrumentId, the FIGI getInstrumentBy resolved.
  def instrumentsService(
    byTicker: Map[String, IO[InstrumentResponse]] = Map.empty,
    dividends: Map[String, IO[GetDividendsResponse]] = Map.empty,
    byBoard: Map[OnBoard, IO[InstrumentResponse]] = Map.empty,
    byFigi: Map[String, IO[InstrumentResponse]] = Map.empty
  ): IO[FakeInstrumentsService] =
    for {
      callsRef <- Ref.of[IO, List[InstrumentRequest]](List.empty)
      dividendsCallsRef <- Ref.of[IO, List[GetDividendsRequest]](List.empty)
    } yield new FakeInstrumentsService {
      def getInstrumentBy(request: InstrumentRequest, ctx: Unit): IO[InstrumentResponse] = {
        val response = request.idType match {
          case InstrumentIdType.INSTRUMENT_ID_TYPE_FIGI => byFigi.get(request.id)
          case InstrumentIdType.INSTRUMENT_ID_TYPE_TICKER =>
            request.classCode
              .flatMap(code => byBoard.get(OnBoard(ClassCode(code), Ticker(request.id))))
              .orElse(byTicker.get(request.id))
          case other =>
            Some(IO.raiseError(new UnsupportedOperationException(s"no fake lookup by $other")))
        }
        callsRef.update(_ :+ request) >> response.getOrElse(
          IO.raiseError(new NoSuchElementException(s"no fake instrument for ${request.id}"))
        )
      }

      def getInstrumentByCalls: IO[List[InstrumentRequest]] = callsRef.get

      def getDividends(request: GetDividendsRequest, ctx: Unit): IO[GetDividendsResponse] =
        dividendsCallsRef.update(_ :+ request) >> dividends.getOrElse(
          request.instrumentId,
          IO.pure(GetDividendsResponse())
        )

      def getDividendsCalls: IO[List[GetDividendsRequest]] = dividendsCallsRef.get

      def tradingSchedules(
        request: TradingSchedulesRequest,
        ctx: Unit
      ): IO[TradingSchedulesResponse] = unimplemented("tradingSchedules")
      def bondBy(request: InstrumentRequest, ctx: Unit): IO[BondResponse] =
        unimplemented("bondBy")
      def bonds(request: InstrumentsRequest, ctx: Unit): IO[BondsResponse] =
        unimplemented("bonds")
      def getBondCoupons(request: GetBondCouponsRequest, ctx: Unit): IO[GetBondCouponsResponse] =
        unimplemented("getBondCoupons")
      def currencyBy(request: InstrumentRequest, ctx: Unit): IO[CurrencyResponse] =
        unimplemented("currencyBy")
      def currencies(request: InstrumentsRequest, ctx: Unit): IO[CurrenciesResponse] =
        unimplemented("currencies")
      def etfBy(request: InstrumentRequest, ctx: Unit): IO[EtfResponse] =
        unimplemented("etfBy")
      def etfs(request: InstrumentsRequest, ctx: Unit): IO[EtfsResponse] =
        unimplemented("etfs")
      def futureBy(request: InstrumentRequest, ctx: Unit): IO[FutureResponse] =
        unimplemented("futureBy")
      def futures(request: InstrumentsRequest, ctx: Unit): IO[FuturesResponse] =
        unimplemented("futures")
      def optionBy(request: InstrumentRequest, ctx: Unit): IO[OptionResponse] =
        unimplemented("optionBy")
      def options(request: InstrumentsRequest, ctx: Unit): IO[OptionsResponse] =
        unimplemented("options")
      def shareBy(request: InstrumentRequest, ctx: Unit): IO[ShareResponse] =
        unimplemented("shareBy")
      def shares(request: InstrumentsRequest, ctx: Unit): IO[SharesResponse] =
        unimplemented("shares")
      def getAccruedInterests(
        request: GetAccruedInterestsRequest,
        ctx: Unit
      ): IO[GetAccruedInterestsResponse] = unimplemented("getAccruedInterests")
      def getFuturesMargin(
        request: GetFuturesMarginRequest,
        ctx: Unit
      ): IO[GetFuturesMarginResponse] = unimplemented("getFuturesMargin")
      def getAssetBy(request: AssetRequest, ctx: Unit): IO[AssetResponse] =
        unimplemented("getAssetBy")
      def getAssets(request: AssetsRequest, ctx: Unit): IO[AssetsResponse] =
        unimplemented("getAssets")
      def getFavorites(request: GetFavoritesRequest, ctx: Unit): IO[GetFavoritesResponse] =
        unimplemented("getFavorites")
      def editFavorites(request: EditFavoritesRequest, ctx: Unit): IO[EditFavoritesResponse] =
        unimplemented("editFavorites")
      def getCountries(request: GetCountriesRequest, ctx: Unit): IO[GetCountriesResponse] =
        unimplemented("getCountries")
      def findInstrument(request: FindInstrumentRequest, ctx: Unit): IO[FindInstrumentResponse] =
        unimplemented("findInstrument")
      def getBrands(request: GetBrandsRequest, ctx: Unit): IO[GetBrandsResponse] =
        unimplemented("getBrands")
      def getBrandBy(request: GetBrandRequest, ctx: Unit): IO[Brand] =
        unimplemented("getBrandBy")
      def getBondEvents(request: GetBondEventsRequest, ctx: Unit): IO[GetBondEventsResponse] =
        unimplemented("getBondEvents")
      def optionsBy(request: FilterOptionsRequest, ctx: Unit): IO[OptionsResponse] =
        unimplemented("optionsBy")
      def dfaBy(request: InstrumentRequest, ctx: Unit): IO[DfaResponse] =
        unimplemented("dfaBy")
      def dfas(request: DfasRequest, ctx: Unit): IO[DfasResponse] =
        unimplemented("dfas")
      def indicatives(request: IndicativesRequest, ctx: Unit): IO[IndicativesResponse] =
        unimplemented("indicatives")
      def createFavoriteGroup(
        request: CreateFavoriteGroupRequest,
        ctx: Unit
      ): IO[CreateFavoriteGroupResponse] = unimplemented("createFavoriteGroup")
      def deleteFavoriteGroup(
        request: DeleteFavoriteGroupRequest,
        ctx: Unit
      ): IO[DeleteFavoriteGroupResponse] = unimplemented("deleteFavoriteGroup")
      def getFavoriteGroups(
        request: GetFavoriteGroupsRequest,
        ctx: Unit
      ): IO[GetFavoriteGroupsResponse] = unimplemented("getFavoriteGroups")
      def getAssetFundamentals(
        request: GetAssetFundamentalsRequest,
        ctx: Unit
      ): IO[GetAssetFundamentalsResponse] = unimplemented("getAssetFundamentals")
      def getAssetReports(request: GetAssetReportsRequest, ctx: Unit): IO[GetAssetReportsResponse] =
        unimplemented("getAssetReports")
      def getConsensusForecasts(
        request: GetConsensusForecastsRequest,
        ctx: Unit
      ): IO[GetConsensusForecastsResponse] = unimplemented("getConsensusForecasts")
      def getForecastBy(request: GetForecastRequest, ctx: Unit): IO[GetForecastResponse] =
        unimplemented("getForecastBy")
      def getRiskRates(request: RiskRatesRequest, ctx: Unit): IO[RiskRatesResponse] =
        unimplemented("getRiskRates")
      def getInsiderDeals(request: GetInsiderDealsRequest, ctx: Unit): IO[GetInsiderDealsResponse] =
        unimplemented("getInsiderDeals")
      def structuredNoteBy(request: InstrumentRequest, ctx: Unit): IO[StructuredNoteResponse] =
        unimplemented("structuredNoteBy")
      def structuredNotes(request: InstrumentsRequest, ctx: Unit): IO[StructuredNotesResponse] =
        unimplemented("structuredNotes")
      def news(request: NewsRequest, ctx: Unit): IO[NewsResponse] =
        unimplemented("news")
    }

  // --- MarketDataServiceFs2Grpc (only getCandles / getLastPrices are exercised) ---

  trait FakeMarketDataService extends MarketDataServiceFs2Grpc[IO, Unit] {
    def getCandlesCalls: IO[List[GetCandlesRequest]]
    def getLastPricesCalls: IO[List[GetLastPricesRequest]]
  }

  // Keyed by GetCandlesRequest.instrumentId / GetLastPricesRequest.instrumentId.headOption (the
  // FIGI TcsMarketDataSource requests by, after resolving it from the ticker).
  def marketDataService(
    candles: Map[String, IO[GetCandlesResponse]] = Map.empty,
    lastPrices: Map[String, IO[GetLastPricesResponse]] = Map.empty
  ): IO[FakeMarketDataService] =
    for {
      candlesCallsRef <- Ref.of[IO, List[GetCandlesRequest]](List.empty)
      lastPricesCallsRef <- Ref.of[IO, List[GetLastPricesRequest]](List.empty)
    } yield new FakeMarketDataService {
      def getCandles(request: GetCandlesRequest, ctx: Unit): IO[GetCandlesResponse] =
        candlesCallsRef.update(_ :+ request) >> candles.getOrElse(
          request.instrumentId.getOrElse(""),
          IO.pure(GetCandlesResponse())
        )

      def getLastPrices(request: GetLastPricesRequest, ctx: Unit): IO[GetLastPricesResponse] =
        lastPricesCallsRef.update(_ :+ request) >> lastPrices.getOrElse(
          request.instrumentId.headOption.getOrElse(""),
          IO.pure(GetLastPricesResponse())
        )

      def getCandlesCalls: IO[List[GetCandlesRequest]] = candlesCallsRef.get
      def getLastPricesCalls: IO[List[GetLastPricesRequest]] = lastPricesCallsRef.get

      def getOrderBook(request: GetOrderBookRequest, ctx: Unit): IO[GetOrderBookResponse] =
        unimplemented("getOrderBook")
      def getTradingStatus(
        request: GetTradingStatusRequest,
        ctx: Unit
      ): IO[GetTradingStatusResponse] = unimplemented("getTradingStatus")
      def getTradingStatuses(
        request: GetTradingStatusesRequest,
        ctx: Unit
      ): IO[GetTradingStatusesResponse] = unimplemented("getTradingStatuses")
      def getLastTrades(request: GetLastTradesRequest, ctx: Unit): IO[GetLastTradesResponse] =
        unimplemented("getLastTrades")
      def getClosePrices(request: GetClosePricesRequest, ctx: Unit): IO[GetClosePricesResponse] =
        unimplemented("getClosePrices")
      def getTechAnalysis(request: GetTechAnalysisRequest, ctx: Unit): IO[GetTechAnalysisResponse] =
        unimplemented("getTechAnalysis")
      def getMarketValues(request: GetMarketValuesRequest, ctx: Unit): IO[GetMarketValuesResponse] =
        unimplemented("getMarketValues")
    }
}
