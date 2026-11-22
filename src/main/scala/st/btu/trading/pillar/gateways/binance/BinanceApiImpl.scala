package st.btu.trading.pillar.gateways.binance

import cats.effect.{Async, Sync, Temporal}
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import io.circe.{Json, JsonObject}
import sttp.client3.SttpBackend
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.security.Crypto
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.{Instant, LocalDate}

/** A [[BinanceApi]] over HTTP for one key.
  *
  * Signed requests carry the key in `X-MBX-APIKEY` and a signature over their query string, whose
  * `timestamp` is the local time moved by the offset to Binance's clock, read from `/api/v3/time`
  * before the first signed call and again when Binance answers that a timestamp is outside the
  * receive window. The query string is built once, percent-encoded, signed as it is and sent as it
  * is. The market data calls it inherits from [[BinanceMarket]] carry no key.
  *
  * Calls are paced by request weight: each call keeps the next call of its bucket waiting for the
  * share of the bucket's limit that its weight takes, scaled by the configured budget. Build one
  * [[BinanceIpBuckets]] for the process and share it between the clients, since the limits per IP
  * address are shared by every key calling from it. A 429 and a 5xx are tried again after a wait,
  * as is a call that gets no answer, up to the attempts configured. `Retry-After` is not read, as
  * it has been seen to say 0. A 418, the ban of the IP address, fails the call at once.
  *
  * The query string, the signature and the keys are never logged or put in an error. A call that
  * fails on the way is described by the cause of the failure alone, because the exception of the
  * HTTP client carries the request.
  */
final class BinanceApiImpl[F[_]: Logging] private (
  market: BinanceMarket[F],
  transport: BinanceTransport[F],
  signer: BinanceSigner[F]
)(implicit F: Temporal[F])
    extends BinanceApi[F] {
  import BinanceApiImpl.*

  def exchangeInfo: F[List[BinancePair]] = market.exchangeInfo

  def minuteClose(symbol: BinanceSymbol, minute: Instant): F[Option[BigDecimal]] =
    market.minuteClose(symbol, minute)

  def dailyBars(symbol: BinanceSymbol, from: LocalDate, to: LocalDate): F[List[BinanceDailyBar]] =
    market.dailyBars(symbol, from, to)

  def lastPrice(symbol: BinanceSymbol): F[Option[BigDecimal]] = market.lastPrice(symbol)

  def apiRestrictions: F[BinanceKeyPermissions] =
    call(SignedEndpoint.KeyRestrictions, Nil).flatMap(BinanceResponses.permissions(_).liftTo[F])

  def delistSchedule: F[List[BinanceSymbol]] =
    call(SignedEndpoint.DelistSchedule, Nil).flatMap(BinanceResponses.delisted(_).liftTo[F])

  def coins: F[Map[BinanceAsset, String]] =
    call(SignedEndpoint.Coins, Nil).flatMap(BinanceResponses.coinNames(_).liftTo[F])

  def myTrades(symbol: BinanceSymbol, fromId: BinanceTradeId): F[List[JsonObject]] =
    call(
      SignedEndpoint.MyTrades,
      List(
        Parameter.Symbol -> symbol.value,
        Parameter.FromId -> fromId.value.toString,
        Parameter.Limit -> BinanceApi.TradesPageSize.toString
      )
    ).flatMap(BinanceResponses.objects(_).liftTo[F])
      .recover { case BinanceError.Refused(BinanceError.InvalidSymbol, _) => List.empty }

  def deposits(from: Instant, to: Instant): F[List[JsonObject]] =
    info"Reading the Binance deposits from $from to $to" >>
      byOffset(SignedEndpoint.Deposits, window(Parameter.StartTime, Parameter.EndTime, from, to))

  def withdrawals(from: Instant, to: Instant): F[List[JsonObject]] =
    info"Reading the Binance withdrawals from $from to $to" >>
      byOffset(SignedEndpoint.Withdrawals, window(Parameter.StartTime, Parameter.EndTime, from, to))

  def fiatOrders(direction: FiatOrderDirection, from: Instant, to: Instant): F[List[JsonObject]] =
    info"Reading the Binance fiat ${direction.toString} orders from $from to $to" >>
      byPage(
        SignedEndpoint.FiatOrders,
        Parameter.TransactionType -> direction.code.toString :: window(
          Parameter.BeginTime,
          Parameter.EndTime,
          from,
          to
        )
      )

  def fiatPayments(
    direction: FiatPaymentDirection,
    from: Instant,
    to: Instant
  ): F[List[JsonObject]] =
    info"Reading the Binance fiat ${direction.toString} payments from $from to $to" >>
      byPage(
        SignedEndpoint.FiatPayments,
        Parameter.TransactionType -> direction.code.toString :: window(
          Parameter.BeginTime,
          Parameter.EndTime,
          from,
          to
        )
      )

  def convertTradeFlow(from: Instant, to: Instant): F[List[JsonObject]] =
    info"Reading the Binance Convert orders from $from to $to" >>
      convertWindow(from.toEpochMilli, to.toEpochMilli)

  def spotBalances: F[Map[BinanceAsset, BigDecimal]] =
    call(SignedEndpoint.SpotAccount, List(Parameter.OmitZeroBalances -> "true"))
      .flatMap(BinanceResponses.spotBalances(_).liftTo[F])

  def fundingBalances: F[Map[BinanceAsset, BigDecimal]] =
    call(SignedEndpoint.FundingAsset, Nil).flatMap(BinanceResponses.fundingBalances(_).liftTo[F])

  private def call(endpoint: SignedEndpoint, parameters: List[(String, String)]): F[Json] =
    transport.signed(endpoint, parameters, signer)

  private def window(
    startName: String,
    endName: String,
    from: Instant,
    to: Instant
  ): List[(String, String)] =
    List(startName -> from.toEpochMilli.toString, endName -> to.toEpochMilli.toString)

  // Reads every page of a history that is paged by the offset of its first row, up to a short page.
  private def byOffset(
    endpoint: SignedEndpoint,
    parameters: List[(String, String)]
  ): F[List[JsonObject]] = {
    def from(offset: Int, collected: Vector[JsonObject]): F[List[JsonObject]] =
      call(
        endpoint,
        parameters ++ List(
          Parameter.Offset -> offset.toString,
          Parameter.Limit -> HistoryPageSize.toString
        )
      ).flatMap(BinanceResponses.objects(_).liftTo[F]).flatMap { page =>
        val all = collected ++ page
        if (page.size < HistoryPageSize) F.pure(all.toList) else from(offset + page.size, all)
      }
    from(0, Vector.empty)
  }

  // Reads every page of a history that is paged by page number, up to a short page.
  private def byPage(
    endpoint: SignedEndpoint,
    parameters: List[(String, String)]
  ): F[List[JsonObject]] = {
    def from(page: Int, collected: Vector[JsonObject]): F[List[JsonObject]] =
      call(
        endpoint,
        parameters ++ List(Parameter.Page -> page.toString, Parameter.Rows -> FiatPageSize.toString)
      )
        .flatMap(BinanceResponses.fiatRecords(_).liftTo[F])
        .flatMap { rows =>
          val all = collected ++ rows
          if (rows.size < FiatPageSize) F.pure(all.toList) else from(page + 1, all)
        }
    from(1, Vector.empty)
  }

  // A window that holds more orders than one answer is read as two halves.
  private def convertWindow(startMillis: Long, endMillis: Long): F[List[JsonObject]] =
    call(
      SignedEndpoint.ConvertTradeFlow,
      List(
        Parameter.StartTime -> startMillis.toString,
        Parameter.EndTime -> endMillis.toString,
        Parameter.Limit -> HistoryPageSize.toString
      )
    ).flatMap(BinanceResponses.convertPage(_).liftTo[F]).flatMap { page =>
      if (!page.moreData) F.pure(page.orders)
      else if (endMillis <= startMillis)
        F.raiseError(
          BinanceError.UnexpectedResponse("one millisecond of Convert orders exceeds an answer")
        )
      else {
        val middle = startMillis + (endMillis - startMillis) / 2
        for {
          first <- convertWindow(startMillis, middle)
          second <- convertWindow(middle + 1, endMillis)
        } yield first ++ second
      }
    }
}

object BinanceApiImpl {

  // How many rows one answer of a history paged by offset, and of Convert, holds at most.
  private val HistoryPageSize = 1000

  // How many rows one answer of a fiat history holds at most.
  private val FiatPageSize = 500

  /** Wires a [[BinanceApi]] that reads with the key `apiKey` and signs with `secretKey` through
    * `backend`, paced by `config`. The limits per IP address are counted in `ipBuckets`, which the
    * clients of every account share; the limits per account are counted in buckets of the client's
    * own.
    */
  def create[I[_]: Sync, F[_]: Async](
    apiKey: ApiKey,
    secretKey: SecretKey,
    config: BinanceApiConfig,
    crypto: Crypto[F],
    backend: SttpBackend[F, Any],
    ipBuckets: BinanceIpBuckets[F]
  )(implicit logs: Logs[I, F]): I[BinanceApi[F]] =
    for {
      accountPacers <- BinanceIpBuckets.pacersOf[I, F](Scope.Account)
      api <- logs.forService[BinanceApi[F]].flatMap { implicit log =>
        val transport = new BinanceTransport[F](config, backend, ipBuckets.pacers ++ accountPacers)
        BinanceSigner
          .create[I, F](apiKey, secretKey, config.recvWindow, crypto, serverTime(transport))
          .map { signer =>
            new BinanceApiImpl[F](new BinanceMarketImpl[F](transport), transport, signer)
          }
      }
    } yield api

  // Binance's clock, in milliseconds since the epoch, which signed requests are stamped by.
  private def serverTime[F[_]: Temporal](transport: BinanceTransport[F]): F[Long] =
    transport.public(PublicEndpoint.Time, Nil).flatMap(BinanceResponses.serverTime(_).liftTo[F])
}
