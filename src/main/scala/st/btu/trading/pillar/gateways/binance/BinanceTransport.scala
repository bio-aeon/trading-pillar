package st.btu.trading.pillar.gateways.binance

import cats.effect.Temporal
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.monadError.*
import io.circe.{Json, parser}
import sttp.client3.*
import sttp.model.{StatusCode, Uri}
import st.btu.trading.pillar.gateways.CallPacer
import st.btu.trading.pillar.models.binance.ApiKey
import tofu.logging.Logging
import tofu.syntax.logging.*

import scala.concurrent.duration.*
import scala.util.control.NonFatal

// What one attempt of a call sends: the address with its query as it was signed, the key the call
// carries when it carries one, and what an error or a log must not repeat.
private[binance] final case class Prepared(uri: Uri, apiKey: Option[ApiKey], secrets: List[String])

// What the calls of every Binance client have in common: they wait for their turn in their bucket,
// are tried again where Binance or the network asks for it, and have their answers classified and
// their secrets taken out of whatever is logged or raised. A call of a signed endpoint is signed by
// the signer of the client that makes it, and a call of a public one carries neither the key nor a
// signature. The query string, the signature and the keys are never logged or put in an error.
private[binance] final class BinanceTransport[F[_]: Logging](
  config: BinanceApiConfig,
  backend: SttpBackend[F, Any],
  pacers: Map[Bucket, CallPacer[F]]
)(implicit F: Temporal[F]) {
  import BinanceTransport.*

  // What one call of an endpoint came to.
  private enum Reply {
    case Answer(json: Json)
    case Refusal(refused: BinanceError.Refused)
    case Throttled
    case ServerError(status: Int)
    case Banned
  }

  /** The answer to a call of `endpoint`, which carries neither the key nor a signature, tried again
    * where Binance or the network asks for it, up to the attempts configured.
    */
  def public(endpoint: PublicEndpoint, parameters: List[(String, String)]): F[Json] = {
    val prepared =
      Prepared(BinanceQuery.withQuery(endpoint.uri, BinanceQuery.encode(parameters)), None, Nil)
    call(endpoint, F.pure(prepared), None)
  }

  /** The answer to a call of `endpoint`, signed by `signer`, tried again as a public call is. A
    * refusal for a timestamp outside the receive window is tried again once, with the offset to
    * Binance's clock read again, whatever the attempts used.
    */
  def signed(
    endpoint: SignedEndpoint,
    parameters: List[(String, String)],
    signer: BinanceSigner[F]
  ): F[Json] =
    call(endpoint, signer.prepare(endpoint, parameters), Some(signer.readOffset))

  private def call(endpoint: Endpoint, prepare: F[Prepared], resync: Option[F[Unit]]): F[Json] = {
    def attempt(number: Int, resyncsLeft: Int): F[Json] = {
      def tryAgain(status: Int, delay: FiniteDuration): F[Json] =
        if (number >= config.maxAttempts)
          warn"Binance ${endpoint.path} still answered HTTP $status after $number attempts" >>
            F.raiseError(BinanceError.RetriesExhausted(status, number))
        else
          info"Binance ${endpoint.path} answered HTTP $status; trying again in $delay" >>
            F.sleep(delay) >> attempt(number + 1, resyncsLeft)

      exchange(endpoint, prepare).attempt.flatMap {
        case Right(Reply.Answer(json)) => F.pure(json)
        case Right(Reply.Refusal(refused)) =>
          resync.filter(_ => isClockRefusal(refused) && resyncsLeft > 0) match {
            case Some(readOffset) =>
              warn"Binance ${endpoint.path} refused the timestamp: ${refused.reason}" >>
                readOffset >> attempt(number, resyncsLeft - 1)
            case None =>
              warn"Binance refused ${endpoint.path} with ${refused.code}: ${refused.reason}" >>
                F.raiseError(refused)
          }
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

  // One call: waits for its turn in its bucket, builds the request as late as possible so that a
  // timestamp in it is fresh when it leaves, and sends it.
  private def exchange(endpoint: Endpoint, prepare: F[Prepared]): F[Reply] =
    for {
      _ <- awaitTurn(endpoint)
      prepared <- prepare
      response <- send(endpoint, prepared)
      reply <- classify(response, prepared.secrets)
    } yield reply

  // A bucket counted per IP address is shared with the other clients, and one counted per account
  // belongs to the client of that account. The call keeps the next one of its bucket waiting for
  // the share of the limit its weight takes, which the budget scales up: a budget of half doubles
  // the spacing.
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

  // Sends the request. Whatever fails on the way is raised as `Unreachable`, described by its cause
  // only: sttp's exceptions carry the request in their message, and so a URI with the signature.
  private def send(endpoint: Endpoint, prepared: Prepared): F[Response[String]] = {
    val request = basicRequest.method(endpoint.method, prepared.uri).response(asStringAlways)
    val keyed = prepared.apiKey.fold(request)(key => request.header(ApiKeyHeader, key.value))
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

private[binance] object BinanceTransport {

  private val ApiKeyHeader = "X-MBX-APIKEY"

  // How often a call whose timestamp Binance refused reads the offset to its clock again.
  private val MaxOffsetReadings = 1

  private val BanStatus = 418

  private val Redacted = "***"
}
