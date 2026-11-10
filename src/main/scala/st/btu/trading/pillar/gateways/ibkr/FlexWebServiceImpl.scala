package st.btu.trading.pillar.gateways.ibkr

import cats.effect.{Async, Sync, Temporal}
import cats.syntax.applicativeError.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.monadError.*
import sttp.client3.*
import sttp.model.{HeaderNames, StatusCode, Uri}
import st.btu.trading.pillar.models.ibkr.{FlexQueryId, FlexToken}
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** A [[FlexWebService]] over HTTP for one token.
  *
  * Every call made with the token, whichever statement it belongs to, waits for its turn at one
  * limiter, so that calls are at least `FlexWebServiceConfig.minSpacing` apart however many
  * statements are read at once. Build one instance per token and share it between the accounts that
  * read with it.
  *
  * The token travels in the query string of every request, as IB's service requires, so no request
  * URL, token or reference code is ever logged or put in an error; the logs name the query, the
  * dates and the errors. A call that fails on the way is described by the cause of the failure
  * alone, because the exception of the HTTP client carries the request.
  */
final class FlexWebServiceImpl[F[_]: Logging] private (
  token: FlexToken,
  config: FlexWebServiceConfig,
  backend: SttpBackend[F, Any],
  pacer: CallPacer[F]
)(implicit F: Temporal[F])
    extends FlexWebService[F] {
  import FlexWebServiceImpl.*

  def statement(queryId: FlexQueryId, from: LocalDate, to: LocalDate): F[String] =
    for {
      _ <- info"Requesting the Flex statement of query $queryId for $from to $to"
      referenceCode <- untilAnswered(SendRequestStep, queryId)(sendRequest(queryId, from, to))
      xml <- untilAnswered(GetStatementStep, queryId)(getStatement(referenceCode))
      _ <- info"Received the Flex statement of query $queryId for $from to $to"
    } yield xml

  private def sendRequest(
    queryId: FlexQueryId,
    from: LocalDate,
    to: LocalDate
  ): F[Either[FlexResponse.Refused, String]] =
    call(
      SendRequestPath,
      TokenParameter -> token.value,
      QueryParameter -> queryId.value,
      FromParameter -> DateTimeFormatter.BASIC_ISO_DATE.format(from),
      ToParameter -> DateTimeFormatter.BASIC_ISO_DATE.format(to)
    ).flatMap {
      case FlexResponse.Accepted(referenceCode) => F.pure(Right(referenceCode))
      case refused: FlexResponse.Refused => F.pure(Left(refused))
      case _: FlexResponse.Statement =>
        F.raiseError(FlexError.UnexpectedResponse("SendRequest answered with a statement"))
    }

  private def getStatement(referenceCode: String): F[Either[FlexResponse.Refused, String]] =
    call(GetStatementPath, TokenParameter -> token.value, QueryParameter -> referenceCode).flatMap {
      case FlexResponse.Statement(xml) => F.pure(Right(xml))
      case refused: FlexResponse.Refused => F.pure(Left(refused))
      case _: FlexResponse.Accepted =>
        F.raiseError(FlexError.UnexpectedResponse("GetStatement answered with a reference code"))
    }

  // Calls `tryOnce` until it is answered. An answer that IB asks to be tried again, and a call that
  // got no answer, are tried again after a wait, up to the attempts allowed. A refusal that waiting
  // does not change, and any other failure, end it.
  private def untilAnswered[A](step: String, queryId: FlexQueryId)(
    tryOnce: F[Either[FlexResponse.Refused, A]]
  ): F[A] = {
    def go(attemptNumber: Int): F[A] = {
      def afterWaiting(delay: FiniteDuration): F[A] = F.sleep(delay) >> go(attemptNumber + 1)

      tryOnce.attempt.flatMap {
        case Right(Right(answer)) => F.pure(answer)
        case Right(Left(refused)) =>
          whenRefused(step, queryId, refused, attemptNumber)(afterWaiting)
        case Left(unreachable: FlexError.Unreachable) =>
          whenUnreachable(step, queryId, unreachable, attemptNumber)(afterWaiting)
        case Left(failure) => F.raiseError(failure)
      }
    }
    go(1)
  }

  private def whenRefused[A](
    step: String,
    queryId: FlexQueryId,
    refused: FlexResponse.Refused,
    attemptNumber: Int
  )(afterWaiting: FiniteDuration => F[A]): F[A] = {
    val FlexResponse.Refused(code, message) = refused
    delayAfter(code) match {
      case None =>
        warn"Flex query $queryId: $step was refused with $code: $message" >>
          F.raiseError(FlexError.Permanent(code, message))
      case Some(_) if attemptNumber >= config.maxAttempts =>
        warn"Flex query $queryId: $step still answered $code after $attemptNumber attempts" >>
          F.raiseError(FlexError.RetriesExhausted(code, message, attemptNumber))
      case Some(delay) =>
        info"Flex query $queryId: $step answered $code: $message; trying again in $delay" >>
          afterWaiting(delay)
    }
  }

  private def whenUnreachable[A](
    step: String,
    queryId: FlexQueryId,
    unreachable: FlexError.Unreachable,
    attemptNumber: Int
  )(afterWaiting: FiniteDuration => F[A]): F[A] =
    if (attemptNumber >= config.maxAttempts)
      warn"Flex query $queryId: $step got no answer in $attemptNumber attempts: ${unreachable.detail}" >>
        F.raiseError(unreachable)
    else
      info"Flex query $queryId: $step got no answer: ${unreachable.detail}" >>
        afterWaiting(config.retryDelay)

  private def delayAfter(code: Int): Option[FiniteDuration] =
    if (code == RateLimitedCode) Some(config.rateLimitDelay)
    else if (RetryableCodes(code)) Some(config.retryDelay)
    else None

  private def call(path: String, parameters: (String, String)*): F[FlexResponse] = {
    val uri: Uri =
      BaseUri.addPath(path).addParams(parameters*).addParam(VersionParameter, ApiVersion)
    for {
      _ <- pacer.awaitTurn
      response <- send(uri, parameters.map { case (_, value) => value })
      body <- bodyOf(response)
      parsed <- FlexResponse.parse(body).liftTo[F]
    } yield parsed
  }

  // Sends the request. Whatever fails on the way is raised as `Unreachable`, described by its cause
  // only: sttp's exceptions carry the request in their message, and so a URI with the token.
  // `secrets` are the values of the request's parameters, which are taken out of the description.
  private def send(uri: Uri, secrets: Seq[String]): F[Response[Either[String, String]]] =
    F.catchNonFatal(basicRequest.get(uri).header(HeaderNames.UserAgent, UserAgent).send(backend))
      .flatten
      .adaptError { case NonFatal(failure) => unreachable(failure, secrets) }

  // A failure with no cause is described by the name of its class alone, since its message may be
  // the request.
  private def unreachable(failure: Throwable, secrets: Seq[String]): FlexError.Unreachable = {
    val described = Option(failure.getCause) match {
      case Some(cause) =>
        val named = cause.getClass.getName
        Option(cause.getMessage).fold(named)(message => s"$named: $message")
      case None => failure.getClass.getName
    }
    FlexError.Unreachable(
      secrets
        .filter(_.nonEmpty)
        .foldLeft(described)((text, secret) => text.replace(secret, Redacted))
    )
  }

  // A status that asks for the call to be made again later is a service that could not answer; any
  // other failure status is final.
  private def bodyOf(response: Response[Either[String, String]]): F[String] =
    response.body match {
      case Right(body) => F.pure(body)
      case Left(_) =>
        val status = response.code
        val detail = s"HTTP status ${status.code}"
        if (status == StatusCode.TooManyRequests || status.isServerError)
          F.raiseError(FlexError.Unreachable(detail))
        else F.raiseError(FlexError.UnexpectedResponse(detail))
    }
}

object FlexWebServiceImpl {

  private val BaseUri: Uri =
    uri"https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService"

  private val SendRequestPath = "SendRequest"
  private val GetStatementPath = "GetStatement"
  private val SendRequestStep = "SendRequest"
  private val GetStatementStep = "GetStatement"

  // The query parameters of both calls: the token, then the query id (SendRequest) or the reference
  // code (GetStatement), the first and the last report date, and the version of the service.
  private val TokenParameter = "t"
  private val QueryParameter = "q"
  private val FromParameter = "fd"
  private val ToParameter = "td"
  private val VersionParameter = "v"

  private val ApiVersion = "3"
  private val UserAgent = "trading-pillar"

  private val Redacted = "***"

  private val RateLimitedCode = 1018

  // IB's answers that ask for the same call to be made again later: the statement could not be
  // generated, is incomplete or still being generated, its settlement or P/L data is not ready, the
  // server is busy, the rate limit was exceeded, or the statement could not be fetched.
  private val RetryableCodes: Set[Int] = Set(
    FlexError.StatementNotGenerated,
    1004,
    1005,
    1006,
    1007,
    1008,
    1009,
    RateLimitedCode,
    1019,
    1021
  )

  /** Wires a [[FlexWebService]] that reads with `token` through `backend`, paced by `config`. Use
    * one for all the accounts that read with the same token.
    */
  def create[I[_]: Sync, F[_]: Async](
    token: FlexToken,
    config: FlexWebServiceConfig,
    backend: SttpBackend[F, Any]
  )(implicit logs: Logs[I, F]): I[FlexWebService[F]] =
    for {
      pacer <- CallPacer.create[I, F](config.minSpacing)
      service <- logs
        .forService[FlexWebService[F]]
        .map(implicit log => new FlexWebServiceImpl[F](token, config, backend, pacer))
    } yield service
}
