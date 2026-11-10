package st.btu.trading.pillar.gateways.ibkr

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{IO, Ref}
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import sttp.client3.{Response, SttpClientException, basicRequest, UriContext}
import sttp.client3.impl.cats.implicits.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import st.btu.trading.pillar.models.ibkr.{FlexQueryId, FlexToken}
import st.btu.trading.pillar.testkit.LogFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.logging.{Logging, Logs}

import java.io.IOException
import java.net.ConnectException
import java.time.LocalDate
import scala.concurrent.duration.*

class FlexWebServiceImplSpec extends Specification with CatsEffect {

  private val plainLogging: Logging[IO] =
    Logging.Make.plain[IO].forService[FlexWebService[IO]]

  private val token = FlexToken("secret-token")
  private val queryId = FlexQueryId("987654")
  private val from = LocalDate.of(2026, 1, 1)
  private val to = LocalDate.of(2026, 1, 31)

  private val referenceCode = "5544332211"

  private val accepted =
    s"""<FlexStatementResponse timestamp="09 November, 2026 10:00 AM EST">
       |<Status>Success</Status>
       |<ReferenceCode>$referenceCode</ReferenceCode>
       |<Url>https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService/GetStatement</Url>
       |</FlexStatementResponse>""".stripMargin

  private def refused(code: Int, message: String = "Try again", status: String = "Warn") =
    s"""<FlexStatementResponse timestamp="09 November, 2026 10:00 AM EST">
       |<Status>$status</Status>
       |<ErrorCode>$code</ErrorCode>
       |<ErrorMessage>$message</ErrorMessage>
       |</FlexStatementResponse>""".stripMargin

  private val statement =
    """<FlexQueryResponse queryName="Activity" type="AF"><FlexStatements count="0"/></FlexQueryResponse>"""

  private val inProgressMessage = "Statement generation in progress."
  private val inProgress = refused(1019, inProgressMessage)

  private val fast = FlexWebServiceConfig(
    minSpacing = 1.milli,
    retryDelay = 1.milli,
    rateLimitDelay = 1.milli,
    maxAttempts = 3
  )

  // What the service was asked, with the time the call arrived.
  private final case class Call(
    path: String,
    parameters: Map[String, String],
    userAgent: Option[String],
    at: FiniteDuration
  )

  private sealed trait Reply

  private final case class Responds(body: String, status: StatusCode = StatusCode.Ok) extends Reply

  // The call fails with `failure`, as it does when the HTTP client cannot reach the service.
  private final case class Breaks(failure: Exception) extends Reply

  private def answer(body: String): Reply = Responds(body)

  private def statusOf(code: Int): StatusCode = StatusCode.unsafeApply(code)

  // How an HTTP client reports a refused connection: in an exception whose message is the request,
  // and so a URL with the token, around the exception that tells what went wrong.
  private def connectFailure(cause: Exception): Exception =
    new SttpClientException.ConnectException(
      basicRequest.get(
        uri"https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService/SendRequest?t=secret-token&q=987654"
      ),
      cause
    )

  private def refusedConnection: Exception = connectFailure(
    new ConnectException("Connection refused")
  )

  private final case class Fixture(service: FlexWebService[IO], calls: IO[List[Call]]) {
    def statement: IO[String] = service.statement(queryId, from, to)

    def paths: IO[List[String]] = calls.map(_.map(_.path))
  }

  private val SendRequest = "SendRequest"
  private val GetStatement = "GetStatement"

  // Answers the calls of each step in order, with the last reply of the step again for every call
  // beyond.
  private def fixture(
    config: FlexWebServiceConfig,
    send: List[Reply] = List(answer(accepted)),
    fetch: List[Reply] = List(answer(statement)),
    logging: Logging[IO] = plainLogging
  ): IO[Fixture] = {
    implicit val logs: Logs[IO, IO] = Logs.const[IO, IO](logging)
    for {
      remaining <- Ref.of[IO, Map[String, List[Reply]]](
        Map(SendRequest -> send, GetStatement -> fetch)
      )
      calls <- Ref.of[IO, List[Call]](List.empty)
      backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest.thenRespondF { request =>
        val step = request.uri.path.lastOption.getOrElse("")
        for {
          now <- IO.monotonic
          _ <- calls.update(
            _ :+ Call(step, request.uri.paramsMap, request.header("User-Agent"), now)
          )
          reply <- remaining.modify { replies =>
            replies(step) match {
              case last :: Nil => (replies, last)
              case next :: rest => (replies.updated(step, rest), next)
              case Nil => (replies, answer(""))
            }
          }
          response <- reply match {
            case Responds(body, status) => IO.pure(Response(body, status))
            case Breaks(failure) => IO.raiseError[Response[String]](failure)
          }
        } yield response
      }
      service <- FlexWebServiceImpl.create[IO, IO](token, config, backend)
    } yield Fixture(service, calls.get)
  }

  "FlexWebServiceImpl" >> {
    "statement" >> {

      "returns the statement once the service has generated it" >> {
        for {
          f <- fixture(fast)
          xml <- f.statement
          paths <- f.paths
        } yield {
          xml must_== statement
          paths must_== List(SendRequest, GetStatement)
        }
      }

      "asks for the statement of the query and the dates, and then fetches it by its reference code" >> {
        for {
          f <- fixture(fast)
          _ <- f.statement
          calls <- f.calls
        } yield {
          calls.map(_.parameters) must_== List(
            Map(
              "t" -> "secret-token",
              "q" -> "987654",
              "fd" -> "20260101",
              "td" -> "20260131",
              "v" -> "3"
            ),
            Map("t" -> "secret-token", "q" -> referenceCode, "v" -> "3")
          )
          calls.map(_.userAgent.isDefined) must_== List(true, true)
        }
      }

      "waits for a statement that is still being generated" >> {
        for {
          f <- fixture(
            fast,
            fetch = List(answer(inProgress), answer(inProgress), answer(statement))
          )
          xml <- f.statement
          paths <- f.paths
        } yield {
          xml must_== statement
          paths must_== List(SendRequest, GetStatement, GetStatement, GetStatement)
        }
      }

      "asks again for the request itself when the service cannot take it yet" >> {
        for {
          f <- fixture(fast, send = List(answer(refused(1009)), answer(accepted)))
          xml <- f.statement
          paths <- f.paths
        } yield {
          xml must_== statement
          paths must_== List(SendRequest, SendRequest, GetStatement)
        }
      }

      "waits longer after the rate limit than after other answers that ask to try again" >> {
        val config = fast.copy(retryDelay = 1.milli, rateLimitDelay = 300.millis)
        for {
          f <- fixture(
            config,
            fetch = List(
              answer(refused(1018, "Too many requests")),
              answer(inProgress),
              answer(statement)
            )
          )
          _ <- f.statement
          times <- f.calls.map(_.map(_.at))
          gaps = times.zip(times.tail).map { case (earlier, later) => later - earlier }
        } yield {
          // after the request, after the rate limit, and after the statement was still in progress
          gaps(1) must be_>=(250.millis)
          gaps(2) must be_<(250.millis)
        }
      }

      "goes by the error code, whatever the status says" >> {
        for {
          f <- fixture(
            fast,
            fetch = List(answer(refused(1019, status = "Fail")), answer(statement))
          )
          xml <- f.statement
        } yield xml must_== statement
      }

      "refusals that waiting does not change" >> {
        val permanent = List(
          1003 -> "Statement is not available.",
          1010 -> "Legacy Flex Queries are no longer supported.",
          1011 -> "Service account is inactive.",
          1012 -> "Token has expired.",
          1013 -> "IP restriction.",
          1014 -> "Query is invalid.",
          1015 -> "Token is invalid.",
          1016 -> "Account in invalid.",
          1017 -> "Reference code is invalid.",
          1020 -> "Invalid request or unable to validate request."
        )

        Fragments.foreach(permanent) { case (code, message) =>
          s"$code ends the call at once, with IB's message" >> {
            for {
              f <- fixture(fast, send = List(answer(refused(code, message, status = "Fail"))))
              failure <- f.statement.attempt
              paths <- f.paths
            } yield {
              failure must beLeft[Throwable](FlexError.Permanent(code, message))
              paths must_== List(SendRequest)
            }
          }
        }
      }

      "answers that ask to try again" >> {
        val retried = List(1001, 1004, 1005, 1006, 1007, 1008, 1009, 1018, 1019, 1021)

        Fragments.foreach(retried) { code =>
          s"$code is tried again, up to the attempts allowed, and then reported with the code" >> {
            for {
              f <- fixture(fast, send = List(answer(refused(code, "Try again shortly."))))
              failure <- f.statement.attempt
              paths <- f.paths
            } yield {
              failure must beLeft[Throwable](
                FlexError.RetriesExhausted(code, "Try again shortly.", 3)
              )
              paths must_== List.fill(3)(SendRequest)
            }
          }
        }

        "1001, which IB answers for a window of dates it finds too long, can be told apart" >> {
          for {
            f <- fixture(
              fast,
              send = List(answer(refused(1001, "Statement could not be generated.")))
            )
            failure <- f.statement.attempt
          } yield failure must beLeft[Throwable].like {
            case FlexError.RetriesExhausted(FlexError.StatementNotGenerated, _, _) => ok
          }
        }

        "counts the attempts to fetch the statement apart from those to request it" >> {
          for {
            f <- fixture(
              fast,
              send = List(answer(refused(1009)), answer(refused(1009)), answer(accepted)),
              fetch = List(answer(inProgress))
            )
            failure <- f.statement.attempt
            paths <- f.paths
          } yield {
            failure must beLeft[Throwable](FlexError.RetriesExhausted(1019, inProgressMessage, 3))
            paths must_== List.fill(3)(SendRequest) ++ List.fill(3)(GetStatement)
          }
        }
      }

      "calls that get no answer" >> {
        "are tried again, and the statement is read once the service answers" >> {
          for {
            f <- fixture(
              fast,
              send = List(Breaks(refusedConnection), Breaks(refusedConnection), answer(accepted))
            )
            xml <- f.statement
            paths <- f.paths
          } yield {
            xml must_== statement
            paths must_== List(SendRequest, SendRequest, SendRequest, GetStatement)
          }
        }

        "are tried again when the statement is fetched too" >> {
          for {
            f <- fixture(fast, fetch = List(Breaks(refusedConnection), answer(statement)))
            xml <- f.statement
            paths <- f.paths
          } yield {
            xml must_== statement
            paths must_== List(SendRequest, GetStatement, GetStatement)
          }
        }

        "are given up after the attempts allowed, with the error of the last failure" >> {
          for {
            f <- fixture(fast, send = List(Breaks(refusedConnection)))
            failure <- f.statement.attempt
            paths <- f.paths
          } yield {
            failure must beLeft[Throwable](
              FlexError.Unreachable("java.net.ConnectException: Connection refused")
            )
            paths must_== List.fill(3)(SendRequest)
          }
        }

        "wait the retry delay before the next attempt" >> {
          for {
            f <- fixture(
              fast.copy(retryDelay = 150.millis),
              send = List(Breaks(refusedConnection), answer(accepted))
            )
            _ <- f.statement
            times <- f.calls.map(_.map(_.at))
          } yield times(1) - times.head must be_>=(120.millis)
        }

        "are described by their cause and not by the exception that carries the request" >> {
          val failure = refusedConnection
          for {
            f <- fixture(fast, send = List(Breaks(failure)))
            raised <- f.statement.attempt
          } yield {
            failure.getMessage must contain("secret-token")
            raised must beLeft[Throwable].like { case error =>
              error.getMessage must contain("Connection refused")
              error.getMessage must not(contain("secret-token"))
              error.getCause must beNull
            }
          }
        }

        "have the token and the reference code taken out of what their cause says" >> {
          val cause = new IOException(
            s"unexpected end of stream on GetStatement?t=secret-token&q=$referenceCode"
          )
          for {
            f <- fixture(fast, fetch = List(Breaks(connectFailure(cause))))
            raised <- f.statement.attempt
          } yield raised must beLeft[Throwable].like { case error =>
            error.getMessage must contain("java.io.IOException")
            error.getMessage must not(contain("secret-token"))
            error.getMessage must not(contain(referenceCode))
          }
        }

        "are described by the class alone when they have no cause, since their message may be the request" >> {
          val failure = new RuntimeException("request failed: SendRequest?t=secret-token")
          for {
            f <- fixture(fast, send = List(Breaks(failure)))
            raised <- f.statement.attempt
          } yield raised must beLeft[Throwable](FlexError.Unreachable("java.lang.RuntimeException"))
        }
      }

      "HTTP statuses" >> {
        Fragments.foreach(List(429, 500, 502, 503, 504)) { status =>
          s"$status is tried again, up to the attempts allowed, and then reported as no answer" >> {
            for {
              f <- fixture(fast, send = List(Responds("busy", statusOf(status))))
              failure <- f.statement.attempt
              paths <- f.paths
            } yield {
              failure must beLeft[Throwable](FlexError.Unreachable(s"HTTP status $status"))
              paths must_== List.fill(3)(SendRequest)
            }
          }
        }

        "a status that asks to try again is followed by the statement once the service recovers" >> {
          for {
            f <- fixture(
              fast,
              send = List(Responds("busy", StatusCode.ServiceUnavailable), answer(accepted)),
              fetch = List(Responds("slow", StatusCode.TooManyRequests), answer(statement))
            )
            xml <- f.statement
            paths <- f.paths
          } yield {
            xml must_== statement
            paths must_== List(SendRequest, SendRequest, GetStatement, GetStatement)
          }
        }

        Fragments.foreach(List(400, 401, 403, 404)) { status =>
          s"$status ends the call at once, as an unexpected response" >> {
            for {
              f <- fixture(fast, send = List(Responds("no", statusOf(status))))
              failure <- f.statement.attempt
              paths <- f.paths
            } yield {
              failure must beLeft[Throwable](FlexError.UnexpectedResponse(s"HTTP status $status"))
              paths must_== List(SendRequest)
            }
          }
        }
      }

      "answers that are none of those the service documents" >> {
        def failsAsUnexpected(send: List[Reply], fetch: List[Reply] = List(answer(statement))) =
          for {
            f <- fixture(fast, send, fetch)
            failure <- f.statement.attempt
          } yield failure must beLeft[Throwable].like { case _: FlexError.UnexpectedResponse => ok }

        "a body that is not XML" >> {
          failsAsUnexpected(List(answer("<html>")))
        }

        "an XML body of another kind" >> {
          failsAsUnexpected(List(answer("<html><body>Maintenance</body></html>")))
        }

        "a success without a reference code" >> {
          failsAsUnexpected(
            List(answer("<FlexStatementResponse><Status>Success</Status></FlexStatementResponse>"))
          )
        }

        "an error code that is not a number" >> {
          failsAsUnexpected(
            List(
              answer(
                "<FlexStatementResponse><Status>Fail</Status><ErrorCode>abc</ErrorCode></FlexStatementResponse>"
              )
            )
          )
        }

        "a statement in answer to the request" >> {
          failsAsUnexpected(List(answer(statement)))
        }

        "a reference code in answer to the fetch" >> {
          failsAsUnexpected(List(answer(accepted)), fetch = List(answer(accepted)))
        }
      }

      "never logs the token, the reference code or the request URL" >> {
        for {
          logging <- LogFakes.recordingLogging
          scenarios <- List(
            fixture(fast, logging = logging),
            fixture(fast, fetch = List(answer(inProgress), answer(statement)), logging = logging),
            fixture(fast, fetch = List(answer(inProgress)), logging = logging),
            fixture(
              fast,
              send = List(answer(refused(1012, "Token has expired."))),
              logging = logging
            ),
            fixture(
              fast,
              fetch = List(Responds("boom", StatusCode.InternalServerError)),
              logging = logging
            ),
            fixture(fast, send = List(Breaks(refusedConnection)), logging = logging)
          ).sequence
          _ <- scenarios.traverse(_.statement.attempt)
          logged <- logging.entries
        } yield {
          logged must not(beEmpty)
          val secrets = List("secret-token", referenceCode, "https://", "interactivebrokers", "t=")
          logged.filter(entry => secrets.exists(entry.contains)) must beEmpty
        }
      }

      "never puts the token or the reference code in a failure" >> {
        for {
          unavailable <- fixture(
            fast,
            fetch = List(Responds("boom", StatusCode.InternalServerError))
          )
          refusal <- fixture(fast, send = List(answer(refused(1012, "Token has expired."))))
          exhausted <- fixture(fast, fetch = List(answer(inProgress)))
          unreachable <- fixture(fast, send = List(Breaks(refusedConnection)))
          failures <- List(unavailable, refusal, exhausted, unreachable)
            .traverse(_.statement.attempt)
          messages = failures.map(_.left.toOption.map(_.getMessage).getOrElse(""))
        } yield {
          messages.forall(_.nonEmpty) must beTrue
          messages.filter(m => m.contains("secret-token") || m.contains(referenceCode)) must beEmpty
        }
      }
    }

    "pacing" >> {
      val spacing = 100.millis
      // A call can be seen a little after its turn, which shifts it without moving the turn after
      // it, so a gap is allowed to fall a little short of the spacing.
      val tolerance = 30.millis
      val paced = fast.copy(minSpacing = spacing)

      def gaps(calls: List[Call]): List[FiniteDuration] = {
        val times = calls.map(_.at).sorted
        times.zip(times.drop(1)).map { case (earlier, later) => later - earlier }
      }

      "keeps the calls of one statement at least the spacing apart" >> {
        for {
          f <- fixture(
            paced,
            fetch = List.fill(3)(List(answer(inProgress), answer(statement))).flatten
          )
          _ <- f.statement // warms the code paths up
          warmedUp <- f.calls.map(_.size)
          _ <- f.statement
          calls <- f.calls.map(_.drop(warmedUp))
        } yield {
          calls.size must_== 3
          gaps(calls).filter(_ < spacing - tolerance) must beEmpty
        }
      }

      "keeps the calls of statements read at the same time apart too" >> {
        for {
          f <- fixture(paced)
          _ <- f.statement
          warmedUp <- f.calls.map(_.size)
          _ <- List.fill(3)(f.statement).parSequence
          calls <- f.calls.map(_.drop(warmedUp))
        } yield {
          calls.size must_== 6
          gaps(calls).filter(_ < spacing - tolerance) must beEmpty
        }
      }
    }
  }
}
