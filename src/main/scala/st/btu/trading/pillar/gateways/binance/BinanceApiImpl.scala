package st.btu.trading.pillar.gateways.binance

import cats.effect.{Async, Ref, Sync, Temporal}
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.monadError.*
import io.circe.{Json, JsonObject, parser}
import sttp.client3.*
import sttp.model.Uri.QuerySegment
import sttp.model.{StatusCode, Uri}
import st.btu.trading.pillar.gateways.CallPacer
import st.btu.trading.pillar.models.binance.*
import st.btu.trading.pillar.security.Crypto
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** A [[BinanceApi]] over HTTP for one key.
  *
  * Signed requests carry the key in `X-MBX-APIKEY` and a signature over their query string, whose
  * `timestamp` is the local time moved by the offset to Binance's clock, read from `/api/v3/time`
  * before the first signed call and again when Binance answers that a timestamp is outside the
  * receive window. The query string is built once, percent-encoded, signed as it is and sent as it
  * is.
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
  apiKey: ApiKey,
  secretKey: SecretKey,
  config: BinanceApiConfig,
  crypto: Crypto[F],
  backend: SttpBackend[F, Any],
  pacers: Map[Bucket, CallPacer[F]],
  offset: Ref[F, Option[Long]]
)(implicit F: Temporal[F])
    extends BinanceApi[F] {
  import BinanceApiImpl.*

  def apiRestrictions: F[BinanceKeyPermissions] =
    call(Endpoint.KeyRestrictions, Nil).flatMap(BinanceResponses.permissions(_).liftTo[F])

  def exchangeInfo: F[List[BinancePair]] =
    call(Endpoint.ExchangeInfo, Nil).flatMap(BinanceResponses.pairs(_).liftTo[F])

  def delistSchedule: F[List[BinanceSymbol]] =
    call(Endpoint.DelistSchedule, Nil).flatMap(BinanceResponses.delisted(_).liftTo[F])

  def coins: F[Map[BinanceAsset, String]] =
    call(Endpoint.Coins, Nil).flatMap(BinanceResponses.coinNames(_).liftTo[F])

  def myTrades(symbol: BinanceSymbol, fromId: BinanceTradeId): F[List[JsonObject]] =
    call(
      Endpoint.MyTrades,
      List(
        Parameter.Symbol -> symbol.value,
        Parameter.FromId -> fromId.value.toString,
        Parameter.Limit -> BinanceApi.TradesPageSize.toString
      )
    ).flatMap(BinanceResponses.objects(_).liftTo[F])
      .recover { case BinanceError.Refused(BinanceError.InvalidSymbol, _) => List.empty }

  def deposits(from: Instant, to: Instant): F[List[JsonObject]] =
    info"Reading the Binance deposits from $from to $to" >>
      byOffset(Endpoint.Deposits, window(Parameter.StartTime, Parameter.EndTime, from, to))

  def withdrawals(from: Instant, to: Instant): F[List[JsonObject]] =
    info"Reading the Binance withdrawals from $from to $to" >>
      byOffset(Endpoint.Withdrawals, window(Parameter.StartTime, Parameter.EndTime, from, to))

  def fiatOrders(direction: FiatOrderDirection, from: Instant, to: Instant): F[List[JsonObject]] =
    info"Reading the Binance fiat ${direction.toString} orders from $from to $to" >>
      byPage(
        Endpoint.FiatOrders,
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
        Endpoint.FiatPayments,
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

  def minuteClose(symbol: BinanceSymbol, minute: Instant): F[Option[BigDecimal]] = {
    val start = minute.toEpochMilli
    call(
      Endpoint.Klines,
      List(
        Parameter.Symbol -> symbol.value,
        Parameter.Interval -> OneMinuteInterval,
        Parameter.StartTime -> start.toString,
        Parameter.EndTime -> (start + MinuteMillis - 1).toString,
        Parameter.Limit -> "1"
      )
    ).flatMap(BinanceResponses.close(_).liftTo[F])
      .recover { case BinanceError.Refused(BinanceError.InvalidSymbol, _) => None }
  }

  private def window(
    startName: String,
    endName: String,
    from: Instant,
    to: Instant
  ): List[(String, String)] =
    List(startName -> from.toEpochMilli.toString, endName -> to.toEpochMilli.toString)

  // Reads every page of a history that is paged by the offset of its first row, up to a short page.
  private def byOffset(
    endpoint: Endpoint,
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
    endpoint: Endpoint,
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
      Endpoint.ConvertTradeFlow,
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

  // What one call of an endpoint came to.
  private enum Reply {
    case Answer(json: Json)
    case Refusal(refused: BinanceError.Refused)
    case Throttled
    case ServerError(status: Int)
    case Banned
  }

  // The answer to a call, trying again where Binance or the network asks for it, up to the attempts
  // configured. A refusal for a timestamp outside the receive window is tried again once, with the
  // offset to Binance's clock read again, whatever the attempts used.
  private def call(endpoint: Endpoint, parameters: List[(String, String)]): F[Json] = {
    def attempt(number: Int, resyncsLeft: Int): F[Json] = {
      def tryAgain(status: Int, delay: FiniteDuration): F[Json] =
        if (number >= config.maxAttempts)
          warn"Binance ${endpoint.path} still answered HTTP $status after $number attempts" >>
            F.raiseError(BinanceError.RetriesExhausted(status, number))
        else
          info"Binance ${endpoint.path} answered HTTP $status; trying again in $delay" >>
            F.sleep(delay) >> attempt(number + 1, resyncsLeft)

      exchange(endpoint, parameters).attempt.flatMap {
        case Right(Reply.Answer(json)) => F.pure(json)
        case Right(Reply.Refusal(refused)) =>
          if (isClockRefusal(refused) && isSigned(endpoint) && resyncsLeft > 0)
            warn"Binance ${endpoint.path} refused the timestamp: ${refused.reason}" >>
              readOffset >> attempt(number, resyncsLeft - 1)
          else
            warn"Binance refused ${endpoint.path} with ${refused.code}: ${refused.reason}" >>
              F.raiseError(refused)
        case Right(Reply.Throttled) =>
          tryAgain(StatusCode.TooManyRequests.code, config.rateLimitDelay)
        case Right(Reply.ServerError(status)) => tryAgain(status, config.retryDelay)
        case Right(Reply.Banned) =>
          warn"Binance has banned the IP address on ${endpoint.path}" >>
            F.raiseError(BinanceError.Banned(endpoint.path))
        case Left(unreachable: BinanceError.Unreachable) =>
          if (number >= config.maxAttempts)
            warn"Binance ${endpoint.path} got no answer in $number attempts: ${unreachable.detail}" >>
              F.raiseError(unreachable)
          else
            info"Binance ${endpoint.path} got no answer: ${unreachable.detail}" >>
              F.sleep(config.retryDelay) >> attempt(number + 1, resyncsLeft)
        case Left(unexpected: BinanceError.UnexpectedResponse) =>
          warn"Binance ${endpoint.path} gave an unexpected answer: ${unexpected.detail}" >>
            F.raiseError(unexpected)
        case Left(failure) => F.raiseError(failure)
      }
    }
    attempt(1, MaxOffsetReadings)
  }

  private def isClockRefusal(refused: BinanceError.Refused): Boolean =
    refused.code == BinanceError.TimestampOutsideRecvWindow

  private def isSigned(endpoint: Endpoint): Boolean =
    endpoint.access match {
      case Access.Signed => true
      case Access.Public => false
    }

  // One call: waits for its turn in its bucket, signs the request when it needs it, as late as
  // possible so that the timestamp is fresh when the request leaves, and sends it.
  private def exchange(endpoint: Endpoint, parameters: List[(String, String)]): F[Reply] =
    for {
      _ <- awaitTurn(endpoint)
      prepared <- prepare(endpoint, parameters)
      response <- send(endpoint, prepared)
      reply <- classify(response, prepared.secrets)
    } yield reply

  // A bucket counted per IP address is shared with the other clients, and one counted per account
  // belongs to this client. The call keeps the next one of its bucket waiting for the share of the
  // limit its weight takes, which the budget scales up: a budget of half doubles the spacing.
  private def awaitTurn(endpoint: Endpoint): F[Unit] = {
    val bucket = endpoint.bucket
    val budget = bucket.scope match {
      case Scope.Ip => config.ipBudget
      case Scope.Account => config.accountBudget
    }
    val spacing =
      (config.limitWindow.toNanos.toDouble * endpoint.weight / (bucket.limit * budget)).toLong.nanos
    pacers(bucket).awaitTurn(spacing)
  }

  private final case class Prepared(uri: Uri, secrets: List[String])

  private def prepare(endpoint: Endpoint, parameters: List[(String, String)]): F[Prepared] =
    endpoint.access match {
      case Access.Signed =>
        for {
          timestamp <- currentTimestamp
          signed <- BinanceQuery.signed(
            parameters ++ List(
              Parameter.RecvWindow -> config.recvWindow.toMillis.toString,
              Parameter.Timestamp -> timestamp.toString
            ),
            secretKey,
            crypto
          )
        } yield Prepared(withQuery(endpoint.uri, signed), secretsOf(signed))
      case Access.Public =>
        F.pure(Prepared(withQuery(endpoint.uri, BinanceQuery.encode(parameters)), Nil))
    }

  // The query is sent as it was signed: sttp would encode a query it is given in pieces its own
  // way, and a signature over another encoding is refused.
  private def withQuery(uri: Uri, query: String): Uri =
    if (query.isEmpty) uri else uri.copy(querySegments = List(QuerySegment.Plain(query, identity)))

  // What an error or a log must not repeat: the query, signed and as it was signed, its signature,
  // and the keys.
  private def secretsOf(signedQuery: String): List[String] = {
    val separator = signedQuery.lastIndexOf(SignatureSeparator)
    val query = signedQuery.substring(0, separator)
    val signature = signedQuery.substring(separator + SignatureSeparator.length)
    List(signedQuery, query, signature, secretKey.value, apiKey.value).filter(_.nonEmpty)
  }

  private def currentTimestamp: F[Long] =
    offset.get.flatMap {
      case Some(delta) => F.realTime.map(_.toMillis + delta)
      case None => readOffset >> currentTimestamp
    }

  // The offset from the local clock to Binance's, measured at the middle of the call that asks for
  // Binance's time.
  private def readOffset: F[Unit] =
    for {
      before <- F.realTime
      json <- call(Endpoint.Time, Nil)
      after <- F.realTime
      serverTime <- BinanceResponses.serverTime(json).liftTo[F]
      middle = (before.toMillis + after.toMillis) / 2
      _ <- offset.set(Some(serverTime - middle))
    } yield ()

  // Sends the request. Whatever fails on the way is raised as `Unreachable`, described by its cause
  // only: sttp's exceptions carry the request in their message, and so a URI with the signature.
  private def send(endpoint: Endpoint, prepared: Prepared): F[Response[String]] = {
    val request = basicRequest.get(prepared.uri).response(asStringAlways)
    val keyed = endpoint.access match {
      case Access.Signed => request.header(ApiKeyHeader, apiKey.value)
      case Access.Public => request
    }
    F.catchNonFatal(keyed.send(backend)).flatten.adaptError { case NonFatal(failure) =>
      unreachable(failure, prepared.secrets)
    }
  }

  // A failure with no cause is described by the name of its class alone, since its message may be
  // the request.
  private def unreachable(failure: Throwable, secrets: List[String]): BinanceError.Unreachable = {
    val described = Option(failure.getCause) match {
      case Some(cause) =>
        val named = cause.getClass.getName
        Option(cause.getMessage).fold(named)(message => s"$named: $message")
      case None => failure.getClass.getName
    }
    BinanceError.Unreachable(redact(described, secrets))
  }

  private def classify(response: Response[String], secrets: List[String]): F[Reply] = {
    val status = response.code
    if (status.isSuccess)
      parseBody(response.body).map { json =>
        BinanceResponses.refusalIn(json).fold[Reply](Reply.Answer(json)) { case (code, message) =>
          Reply.Refusal(BinanceError.Refused(code, redact(message, secrets)))
        }
      }
    else if (status == StatusCode.TooManyRequests) F.pure(Reply.Throttled)
    else if (status.code == BanStatus) F.pure(Reply.Banned)
    else if (status.isServerError) F.pure(Reply.ServerError(status.code))
    else
      parser.parse(response.body).toOption.flatMap(BinanceResponses.refusalIn) match {
        case Some((code, message)) =>
          F.pure(Reply.Refusal(BinanceError.Refused(code, redact(message, secrets))))
        case None =>
          F.raiseError(BinanceError.UnexpectedResponse(s"HTTP status ${status.code}"))
      }
  }

  private def parseBody(body: String): F[Json] =
    parser
      .parse(body)
      .leftMap(_ => BinanceError.UnexpectedResponse("the answer is not JSON"))
      .liftTo[F]

  // Longer secrets first, so that a part of one that is a secret of its own is not cut out first.
  private def redact(text: String, secrets: List[String]): String =
    secrets
      .sortBy(-_.length)
      .foldLeft(text)((redacted, secret) => redacted.replace(secret, Redacted))
}

object BinanceApiImpl {

  private val ApiKeyHeader = "X-MBX-APIKEY"

  // The names of the parameters of the calls.
  private object Parameter {
    val BeginTime = "beginTime"
    val EndTime = "endTime"
    val FromId = "fromId"
    val Interval = "interval"
    val Limit = "limit"
    val Offset = "offset"
    val Page = "page"
    val RecvWindow = "recvWindow"
    val Rows = "rows"
    val StartTime = "startTime"
    val Symbol = "symbol"
    val Timestamp = "timestamp"
    val TransactionType = "transactionType"
  }

  // How often a call whose timestamp Binance refused reads the offset to its clock again.
  private val MaxOffsetReadings = 1

  private val SignatureSeparator = "&signature="
  private val OneMinuteInterval = "1m"
  private val MinuteMillis = 60000L

  // How many rows one answer of a history paged by offset, and of Convert, holds at most.
  private val HistoryPageSize = 1000

  // How many rows one answer of a fiat history holds at most.
  private val FiatPageSize = 500

  private val BanStatus = 418

  private val Redacted = "***"

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
      offset <- Ref.in[I, F, Option[Long]](None)
      api <- logs
        .forService[BinanceApi[F]]
        .map(implicit log =>
          new BinanceApiImpl[F](
            apiKey,
            secretKey,
            config,
            crypto,
            backend,
            ipBuckets.pacers ++ accountPacers,
            offset
          )
        )
    } yield api
}
