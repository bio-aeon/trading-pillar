package st.btu.trading.pillar.testkit

import cats.Applicative
import cats.effect.{IO, Ref}
import st.btu.trading.pillar.facades.TcsFacade
import st.btu.trading.pillar.gateways.TgGateway
import st.btu.trading.pillar.models.domain.Portfolio
import st.btu.trading.pillar.models.tg.{ChatId, InlineButton, Result, Update}
import ru.tinkoff.piapi.contract.v1.operations.OperationItem

import java.time.ZonedDateTime

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

  trait FakeTcsFacade[F[_]] extends TcsFacade[F] {
    def setPortfolio(portfolio: Portfolio): F[Unit]
  }

  def tcsFacade(
    portfolioRef: Ref[IO, Portfolio],
    operationsResponse: List[OperationItem] = List.empty
  ): FakeTcsFacade[IO] = new FakeTcsFacade[IO] {
    def portfolio: IO[Portfolio] = portfolioRef.get

    def operations(since: ZonedDateTime, until: ZonedDateTime): IO[List[OperationItem]] =
      IO.pure(operationsResponse)

    def setPortfolio(portfolio: Portfolio): IO[Unit] =
      portfolioRef.set(portfolio)
  }

  def tcsFacade(): IO[FakeTcsFacade[IO]] =
    tcsFacade(Portfolio(List.empty))

  def tcsFacade(portfolio: Portfolio): IO[FakeTcsFacade[IO]] =
    Ref.of[IO, Portfolio](portfolio).map(tcsFacade(_))
}
