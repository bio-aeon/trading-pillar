package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.facades.TcsFacade
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.tg.{ChatId, InlineButton, Result, Update}
import ru.tinkoff.piapi.contract.v1.operations.OperationItem

import java.time.{Instant, ZonedDateTime}

object Fakes {

  case class SentMessage(chatId: ChatId, message: String)

  case class SentKeyboard(chatId: ChatId, message: String, rows: List[List[InlineButton]])

  case class SentDocument(
    chatId: ChatId,
    filename: String,
    content: Array[Byte],
    mimeType: String,
    caption: Option[String]
  )

  case class SentPhoto(
    chatId: ChatId,
    filename: String,
    content: Array[Byte],
    caption: Option[String]
  )

  trait FakeTgGateway[F[_]] extends TgGateway[F] {
    def sentMessages: F[List[SentMessage]]
    def sentKeyboards: F[List[SentKeyboard]]
    def answeredCallbacks: F[List[String]]
    def sentDocuments: F[List[SentDocument]]
    def sentPhotos: F[List[SentPhoto]]
  }

  def tgGateway(
    updatesResponse: Result[List[Update]] = Result(ok = true, result = List.empty)
  ): IO[FakeTgGateway[IO]] =
    for {
      messages <- Ref.of[IO, List[SentMessage]](List.empty)
      keyboards <- Ref.of[IO, List[SentKeyboard]](List.empty)
      answeredCallbackIds <- Ref.of[IO, List[String]](List.empty)
      documents <- Ref.of[IO, List[SentDocument]](List.empty)
      photos <- Ref.of[IO, List[SentPhoto]](List.empty)
    } yield new FakeTgGateway[IO] {
      def sendMessage(chatId: ChatId, msg: String): IO[Unit] =
        messages.update(_ :+ SentMessage(chatId, msg))

      def sendMessageWithKeyboard(
        chatId: ChatId,
        msg: String,
        rows: List[List[InlineButton]]
      ): IO[Unit] =
        keyboards.update(_ :+ SentKeyboard(chatId, msg, rows))

      def answerCallbackQuery(callbackQueryId: String): IO[Unit] =
        answeredCallbackIds.update(_ :+ callbackQueryId)

      def sendDocument(
        chatId: ChatId,
        filename: String,
        content: Array[Byte],
        mimeType: String,
        caption: Option[String]
      ): IO[Unit] =
        documents.update(_ :+ SentDocument(chatId, filename, content, mimeType, caption))

      def sendPhoto(
        chatId: ChatId,
        filename: String,
        content: Array[Byte],
        caption: Option[String]
      ): IO[Unit] =
        photos.update(_ :+ SentPhoto(chatId, filename, content, caption))

      def getUpdates(offset: Long): IO[Result[List[Update]]] =
        IO.pure(updatesResponse)

      def sentMessages: IO[List[SentMessage]] = messages.get

      def sentKeyboards: IO[List[SentKeyboard]] = keyboards.get

      def answeredCallbacks: IO[List[String]] = answeredCallbackIds.get

      def sentDocuments: IO[List[SentDocument]] = documents.get

      def sentPhotos: IO[List[SentPhoto]] = photos.get
    }

  case class RequestedWindow(since: Instant, until: Instant)

  trait FakeTcsFacade extends TcsFacade[IO] {

    /** Replaces what Tinkoff reports from now on. */
    def report(operations: List[OperationItem]): IO[Unit]

    /** The windows `operations` was asked for, oldest first. */
    def requests: IO[List[RequestedWindow]]
  }

  // Answers each request with the reported operations dated within its window, as the broker does.
  def tcsFacade(reported: List[OperationItem] = List.empty): IO[FakeTcsFacade] =
    for {
      reportedRef <- Ref.of[IO, List[OperationItem]](reported)
      requestsRef <- Ref.of[IO, List[RequestedWindow]](List.empty)
    } yield new FakeTcsFacade {
      def operations(since: ZonedDateTime, until: ZonedDateTime): IO[List[OperationItem]] =
        requestsRef.update(
          _ :+ RequestedWindow(since.toInstant, until.toInstant)
        ) >> reportedRef.get.map(_.filter { item =>
          item.date.exists { date =>
            val dated = Instant.ofEpochSecond(date.seconds, date.nanos)
            !dated.isBefore(since.toInstant) && !dated.isAfter(until.toInstant)
          }
        })

      def report(operations: List[OperationItem]): IO[Unit] = reportedRef.set(operations)

      def requests: IO[List[RequestedWindow]] = requestsRef.get
    }
}
