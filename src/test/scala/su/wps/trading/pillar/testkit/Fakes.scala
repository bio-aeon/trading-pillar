package su.wps.trading.pillar.testkit

import cats.Applicative
import cats.effect.{IO, Ref}
import su.wps.trading.pillar.facades.TcsFacade
import su.wps.trading.pillar.gateways.TgGateway
import su.wps.trading.pillar.models.domain.Portfolio
import su.wps.trading.pillar.models.tg.{ChatId, Result, Update}
import ru.tinkoff.piapi.contract.v1.operations.Operation

import java.time.ZonedDateTime

object Fakes {

  case class SentMessage(chatId: ChatId, message: String)

  trait FakeTgGateway[F[_]] extends TgGateway[F] {
    def sentMessages: F[List[SentMessage]]
  }

  def tgGateway(
    messages: Ref[IO, List[SentMessage]],
    updatesResponse: Result[List[Update]] = Result(ok = true, result = List.empty)
  ): FakeTgGateway[IO] = new FakeTgGateway[IO] {
    def sendMessage(chatId: ChatId, msg: String): IO[Unit] =
      messages.update(_ :+ SentMessage(chatId, msg))

    def getUpdates(offset: Long): IO[Result[List[Update]]] =
      IO.pure(updatesResponse)

    def sentMessages: IO[List[SentMessage]] = messages.get
  }

  def tgGateway: IO[FakeTgGateway[IO]] =
    Ref.of[IO, List[SentMessage]](List.empty).map(tgGateway(_))

  trait FakeTcsFacade[F[_]] extends TcsFacade[F] {
    def setPortfolio(portfolio: Portfolio): F[Unit]
  }

  def tcsFacade(
    portfolioRef: Ref[IO, Portfolio],
    operationsResponse: List[Operation] = List.empty
  ): FakeTcsFacade[IO] = new FakeTcsFacade[IO] {
    def portfolio: IO[Portfolio] = portfolioRef.get

    def operations(since: ZonedDateTime, until: ZonedDateTime): IO[List[Operation]] =
      IO.pure(operationsResponse)

    def setPortfolio(portfolio: Portfolio): IO[Unit] =
      portfolioRef.set(portfolio)
  }

  def tcsFacade(): IO[FakeTcsFacade[IO]] =
    tcsFacade(Portfolio(List.empty))

  def tcsFacade(portfolio: Portfolio): IO[FakeTcsFacade[IO]] =
    Ref.of[IO, Portfolio](portfolio).map(tcsFacade(_))
}
