package st.btu.trading.pillar.gateways

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.effect.testing.specs2.CatsEffect
import io.circe.Json
import io.circe.parser.parse
import org.specs2.mutable.Specification
import sttp.client3.impl.cats.implicits.*
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{ByteArrayBody, MultipartBody, StringBody}
import sttp.model.StatusCode
import st.btu.trading.pillar.models.tg
import tofu.Delay
import tofu.logging.Logging

import java.net.URLDecoder

private final case class CapturedRequest(method: String, uri: String, body: Any)

private final case class CapturedPart(
  bytes: Array[Byte],
  fileName: Option[String],
  contentType: Option[String]
)

class TgGatewaySpec extends Specification with CatsEffect {

  implicit val delayForIO: Delay[IO] = new Delay[IO] {
    override def delay[A](a: => A): IO[A] = IO(a)
  }

  implicit val logging: Logging[IO] = Logging.Make.plain[IO].forService[TgGateway[IO]]

  private val token = tg.Token("test-token")
  private val chatId = tg.ChatId(123)

  private val errorGateway: TgGatewayImpl[IO] = {
    val backend =
      SttpBackendStub[IO, Any](implicitly).whenAnyRequest
        .thenRespond("error", StatusCode.BadRequest)
    TgGatewayImpl.create[IO](token, backend)
  }

  // Captures the single request the stub matches, into a Ref readable once the call under test
  // has completed - replaces copying the same `var`-capturing whenRequestMatches block into every
  // example that needs to inspect what was actually sent.
  private def capturingBackend(
    response: String = "",
    status: StatusCode = StatusCode.Ok
  ): (SttpBackendStub[IO, Any], IO[CapturedRequest]) = {
    val captured = Ref.unsafe[IO, Option[CapturedRequest]](None)
    val backend = SttpBackendStub[IO, Any](implicitly)
      .whenRequestMatches { req =>
        val request = CapturedRequest(req.method.method, req.uri.toString, req.body)
        captured.set(Some(request)).unsafeRunSync()
        true
      }
      .thenRespond(response, status)
    (backend, captured.get.map(_.getOrElse(sys.error("no request captured"))))
  }

  // Decodes a captured multipart body's parts by name, for the sendDocument/sendPhoto examples.
  private def multipartParts(body: Any): Map[String, CapturedPart] =
    body match {
      case MultipartBody(parts) =>
        parts.map { p =>
          val bytes = p.body match {
            case StringBody(s, _, _) => s.getBytes
            case ByteArrayBody(b, _) => b
            case _ => Array.emptyByteArray
          }
          p.name -> CapturedPart(bytes, p.fileName, p.contentType)
        }.toMap
      case _ => Map.empty
    }

  // The gateway builds every GET request's query string by hand (no sttp Uri-building syntax),
  // so a request's own params are read back the same way here: split the raw URI at its first
  // "?" and then its "&"s, rather than relying on sttp's Uri query parsing.
  private def queryParam(uri: String, name: String): Option[String] =
    uri.split('?').lift(1).flatMap { query =>
      query.split('&').find(_.startsWith(s"$name=")).map { pair =>
        URLDecoder.decode(pair.drop(name.length + 1), "UTF-8")
      }
    }

  "TgGatewayImpl" >> {
    "getUpdates" >> {

      "parses a successful response with updates" >> {
        val jsonResponse = """{
          "ok": true,
          "result": [
            {"update_id": 123, "message": {"message_id": 1, "chat": {"id": 456}, "text": "hello"}}
          ]
        }"""

        val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
          .thenRespond(jsonResponse)

        val gateway = TgGatewayImpl.create[IO](token, backend)

        gateway.getUpdates(0).map { result =>
          result.ok must beTrue
          result.result must have size 1
          result.result.head.update_id must_== 123
          result.result.head.message must beSome
          result.result.head.message.get.text must beSome("hello")
        }
      }

      "parses a response with an empty updates list" >> {
        val jsonResponse = """{"ok": true, "result": []}"""

        val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
          .thenRespond(jsonResponse)

        val gateway = TgGatewayImpl.create[IO](token, backend)

        gateway.getUpdates(0).map { result =>
          result.ok must beTrue
          result.result must beEmpty
        }
      }

      "parses a callback_query update, including its nested message" >> {
        val jsonResponse = """{
          "ok": true,
          "result": [
            {
              "update_id": 124,
              "callback_query": {
                "id": "cb1",
                "from": {"id": 789},
                "message": {"message_id": 2, "chat": {"id": 456}, "text": "Choose a command:"},
                "data": "/positions"
              }
            }
          ]
        }"""

        val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
          .thenRespond(jsonResponse)

        val gateway = TgGatewayImpl.create[IO](token, backend)

        gateway.getUpdates(0).map { result =>
          result.result must have size 1
          val update = result.result.head
          update.message must beNone
          update.callback_query must beSome
          val cb = update.callback_query.get
          cb.id must_== "cb1"
          cb.from must beSome(tg.User(789))
          cb.data must beSome("/positions")
          cb.message.map(_.chat.id) must beSome(tg.ChatId(456))
        }
      }

      "requests message and callback_query in allowed_updates" >> {
        val (backend, captured) = capturingBackend("""{"ok": true, "result": []}""")
        val gateway = TgGatewayImpl.create[IO](token, backend)

        for {
          _ <- gateway.getUpdates(0)
          req <- captured
        } yield queryParam(req.uri, "allowed_updates") must beSome(
          """["message","callback_query"]"""
        )
      }

      "fails on a non-success HTTP status" >> {
        val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
          .thenRespond("error", StatusCode.InternalServerError)

        val gateway = TgGatewayImpl.create[IO](token, backend)

        gateway.getUpdates(0).attempt.map { result =>
          result must beLeft[Throwable].like { case e =>
            e.getMessage must contain("500")
          }
        }
      }

      "fails on an invalid JSON response" >> {
        val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
          .thenRespond("not json")

        val gateway = TgGatewayImpl.create[IO](token, backend)

        gateway.getUpdates(0).attempt.map { result =>
          result must beLeft[Throwable].like { case e =>
            e.getMessage must contain("Failed to parse")
          }
        }
      }
    }

    "sendMessage" >> {
      "succeeds on an OK response" >> {
        val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
          .thenRespond("", StatusCode.Ok)

        val gateway = TgGatewayImpl.create[IO](token, backend)

        gateway.sendMessage(chatId, "test message").attempt.map { result =>
          result must beRight
        }
      }

      "fails on error response" >>
        errorGateway.sendMessage(chatId, "test message").attempt.map { result =>
          result must beLeft[Throwable].like { case e =>
            e.getMessage must contain("400")
          }
        }
    }

    "sendMessageWithKeyboard" >> {
      "sends chat_id, text and parse_mode plus a reply_markup inline_keyboard matching the given rows" >> {
        val (backend, captured) = capturingBackend()
        val gateway = TgGatewayImpl.create[IO](token, backend)
        val rows = List(
          List(tg.InlineButton("A", "/a"), tg.InlineButton("B", "/b")),
          List(tg.InlineButton("C", "/c"))
        )

        for {
          result <- gateway.sendMessageWithKeyboard(chatId, "pick one", rows).attempt
          req <- captured
        } yield {
          def button(text: String, data: String): Json =
            Json.obj("text" -> Json.fromString(text), "callback_data" -> Json.fromString(data))
          val expected = Json.obj(
            "inline_keyboard" -> Json
              .arr(Json.arr(button("A", "/a"), button("B", "/b")), Json.arr(button("C", "/c")))
          )

          result must beRight
          req.uri must contain("/sendMessage")
          queryParam(req.uri, "chat_id") must beSome("123")
          queryParam(req.uri, "text") must beSome("pick one")
          val markup = queryParam(req.uri, "reply_markup").flatMap(parse(_).toOption)
          markup must beSome(expected)
        }
      }

      "fails on error response" >>
        errorGateway.sendMessageWithKeyboard(chatId, "pick one", List.empty).attempt.map { result =>
          result must beLeft[Throwable].like { case e =>
            e.getMessage must contain("400")
          }
        }
    }

    "answerCallbackQuery" >> {
      "sends the callback_query_id" >> {
        val (backend, captured) = capturingBackend()
        val gateway = TgGatewayImpl.create[IO](token, backend)

        for {
          result <- gateway.answerCallbackQuery("cb1").attempt
          req <- captured
        } yield {
          result must beRight
          req.uri must contain("/answerCallbackQuery")
          queryParam(req.uri, "callback_query_id") must beSome("cb1")
        }
      }

      "fails on error response" >>
        errorGateway.answerCallbackQuery("cb1").attempt.map { result =>
          result must beLeft[Throwable].like { case e =>
            e.getMessage must contain("400")
          }
        }
    }

    "sendDocument" >> {
      "posts a multipart/form-data request with chat_id, the document and its caption" >> {
        val (backend, captured) = capturingBackend()
        val gateway = TgGatewayImpl.create[IO](token, backend)
        val content = Array[Byte](1, 2, 3)

        for {
          result <- gateway
            .sendDocument(chatId, "operations-2025.csv", content, "text/csv", Some("a caption"))
            .attempt
          req <- captured
        } yield {
          val parts = multipartParts(req.body)
          result must beRight
          req.method must_== "POST"
          req.uri must contain("/sendDocument")
          parts.get("chat_id").map(p => new String(p.bytes)) must beSome("123")
          parts.get("document").flatMap(_.fileName) must beSome("operations-2025.csv")
          parts.get("document").flatMap(_.contentType) must beSome("text/csv")
          parts.get("document").map(_.bytes.toList) must beSome(content.toList)
          parts.get("caption").map(p => new String(p.bytes)) must beSome("a caption")
        }
      }

      "omits the caption part when none is given" >> {
        val (backend, captured) = capturingBackend()
        val gateway = TgGatewayImpl.create[IO](token, backend)

        for {
          result <- gateway
            .sendDocument(chatId, "f.csv", Array.emptyByteArray, "text/csv", None)
            .attempt
          req <- captured
        } yield {
          result must beRight
          multipartParts(req.body).contains("caption") must beFalse
        }
      }

      "succeeds on an OK response" >> {
        val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
          .thenRespond("", StatusCode.Ok)

        val gateway = TgGatewayImpl.create[IO](token, backend)

        gateway
          .sendDocument(chatId, "f.csv", Array.emptyByteArray, "text/csv", None)
          .attempt
          .map(result => result must beRight)
      }

      "fails on error response" >>
        errorGateway
          .sendDocument(chatId, "f.csv", Array.emptyByteArray, "text/csv", None)
          .attempt
          .map { result =>
            result must beLeft[Throwable].like { case e =>
              e.getMessage must contain("400")
            }
          }
    }

    "sendPhoto" >> {
      "posts a multipart/form-data request with chat_id, the photo (as image/png) and its caption" >> {
        val (backend, captured) = capturingBackend()
        val gateway = TgGatewayImpl.create[IO](token, backend)
        val content = Array[Byte](1, 2, 3)

        for {
          result <- gateway
            .sendPhoto(chatId, "backtest-7-equity.png", content, Some("a caption"))
            .attempt
          req <- captured
        } yield {
          val parts = multipartParts(req.body)
          result must beRight
          req.method must_== "POST"
          req.uri must contain("/sendPhoto")
          parts.get("chat_id").map(p => new String(p.bytes)) must beSome("123")
          parts.get("photo").flatMap(_.fileName) must beSome("backtest-7-equity.png")
          parts.get("photo").flatMap(_.contentType) must beSome("image/png")
          parts.get("photo").map(_.bytes.toList) must beSome(content.toList)
          parts.get("caption").map(p => new String(p.bytes)) must beSome("a caption")
        }
      }

      "omits the caption part when none is given" >> {
        val (backend, captured) = capturingBackend()
        val gateway = TgGatewayImpl.create[IO](token, backend)

        for {
          result <- gateway.sendPhoto(chatId, "f.png", Array.emptyByteArray, None).attempt
          req <- captured
        } yield {
          result must beRight
          multipartParts(req.body).contains("caption") must beFalse
        }
      }

      "fails on error response" >>
        errorGateway.sendPhoto(chatId, "f.png", Array.emptyByteArray, None).attempt.map { result =>
          result must beLeft[Throwable].like { case e =>
            e.getMessage must contain("400")
          }
        }
    }
  }
}
