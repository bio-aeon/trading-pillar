package su.wps.trading.pillar.gateways

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import sttp.client3.impl.cats.implicits.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import su.wps.trading.pillar.models.tg
import tofu.Delay
import tofu.logging.Logging

class TgGatewaySpec extends Specification with CatsEffect {

  implicit val delayForIO: Delay[IO] = new Delay[IO] {
    override def delay[A](a: => A): IO[A] = IO(a)
  }

  implicit val logging: Logging[IO] = Logging.Make.plain[IO].forService[TgGateway[IO]]

  "TgGatewayImpl.getUpdates" should {
    "parse successful response with updates" in {
      val jsonResponse = """{
        "ok": true,
        "result": [
          {"update_id": 123, "message": {"message_id": 1, "chat": {"id": 456}, "text": "hello"}}
        ]
      }"""

      val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
        .thenRespond(jsonResponse)

      val gateway = TgGatewayImpl.create[IO](tg.Token("test-token"), backend)

      gateway.getUpdates(0).map { result =>
        result.ok must beTrue
        result.result must have size 1
        result.result.head.update_id must_== 123
        result.result.head.message must beSome
        result.result.head.message.get.text must beSome("hello")
      }
    }

    "parse response with empty updates list" in {
      val jsonResponse = """{"ok": true, "result": []}"""

      val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
        .thenRespond(jsonResponse)

      val gateway = TgGatewayImpl.create[IO](tg.Token("test-token"), backend)

      gateway.getUpdates(0).map { result =>
        result.ok must beTrue
        result.result must beEmpty
      }
    }

    "fail on non-success HTTP status" in {
      val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
        .thenRespond("error", StatusCode.InternalServerError)

      val gateway = TgGatewayImpl.create[IO](tg.Token("test-token"), backend)

      gateway.getUpdates(0).attempt.map { result =>
        result must beLeft[Throwable].like { case e =>
          e.getMessage must contain("500")
        }
      }
    }

    "fail on invalid JSON response" in {
      val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
        .thenRespond("not json")

      val gateway = TgGatewayImpl.create[IO](tg.Token("test-token"), backend)

      gateway.getUpdates(0).attempt.map { result =>
        result must beLeft[Throwable].like { case e =>
          e.getMessage must contain("Failed to parse")
        }
      }
    }
  }

  "TgGatewayImpl.sendMessage" should {
    "succeed on OK response" in {
      val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
        .thenRespond("", StatusCode.Ok)

      val gateway = TgGatewayImpl.create[IO](tg.Token("test-token"), backend)

      gateway.sendMessage(tg.ChatId(123), "test message").attempt.map { result =>
        result must beRight
      }
    }

    "fail on error response" in {
      val backend = SttpBackendStub[IO, Any](implicitly).whenAnyRequest
        .thenRespond("error", StatusCode.BadRequest)

      val gateway = TgGatewayImpl.create[IO](tg.Token("test-token"), backend)

      gateway.sendMessage(tg.ChatId(123), "test message").attempt.map { result =>
        result must beLeft[Throwable].like { case e =>
          e.getMessage must contain("400")
        }
      }
    }
  }
}
