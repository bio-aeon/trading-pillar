package st.btu.trading.pillar.gateways

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.tg.{ChatId, InlineButton}

class CapturingTgGatewaySpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val otherChatId = ChatId(2L)

  // Arrays compare by reference, so the content is compared as a list.
  private def comparable(reply: CapturedReply): Any =
    reply match {
      case CapturedReply.Document(fileName, content, caption) =>
        (fileName, content.toList, caption)
      case CapturedReply.Photo(content, caption) => (content.toList, caption)
      case other => other
    }

  "CapturingTgGateway" >> {
    "take" >> {

      "returns what was sent to the chat in the order it was sent" >> {
        for {
          gateway <- CapturingTgGateway.create[IO, IO]
          _ <- gateway.sendMessage(chatId, "first")
          _ <- gateway.sendMessageWithKeyboard(
            chatId,
            "pick one",
            List(
              List(InlineButton("/positions", "/positions"), InlineButton("/alerts", "/alerts")),
              List(InlineButton("/help", "/help"))
            )
          )
          _ <- gateway.sendDocument(
            chatId,
            "export.csv",
            "a,b".getBytes("UTF-8"),
            "text/csv",
            Some("the export")
          )
          _ <- gateway.sendPhoto(chatId, "curve.png", Array[Byte](1, 2, 3), None)
          replies <- gateway.take(chatId)
        } yield replies.map(comparable) must_== List(
          CapturedReply.Text("first"),
          CapturedReply
            .TextWithKeyboard("pick one", List(List("/positions", "/alerts"), List("/help"))),
          ("export.csv", List[Byte](97, 44, 98), Some("the export")),
          (List[Byte](1, 2, 3), None)
        )
      }

      "keeps what was sent to different chats apart" >> {
        for {
          gateway <- CapturingTgGateway.create[IO, IO]
          _ <- gateway.sendMessage(chatId, "for one")
          _ <- gateway.sendMessage(otherChatId, "for two")
          forOne <- gateway.take(chatId)
          forTwo <- gateway.take(otherChatId)
        } yield (forOne, forTwo) must_== ((
          List(CapturedReply.Text("for one")),
          List(CapturedReply.Text("for two"))
        ))
      }

      "removes the replies it returns" >> {
        for {
          gateway <- CapturingTgGateway.create[IO, IO]
          _ <- gateway.sendMessage(chatId, "hello")
          first <- gateway.take(chatId)
          second <- gateway.take(chatId)
        } yield (first, second) must_== ((List(CapturedReply.Text("hello")), Nil))
      }

      "returns nothing for a chat nothing was sent to" >> {
        CapturingTgGateway
          .create[IO, IO]
          .flatMap(_.take(chatId))
          .map(_ must beEmpty)
      }
    }
  }
}
