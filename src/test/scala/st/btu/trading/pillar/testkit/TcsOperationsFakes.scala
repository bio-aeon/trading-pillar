package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import io.grpc.Metadata
import ru.tinkoff.piapi.contract.v1.operations.*

// A fake implementing the generated operations service trait directly (ctx type Metadata, matching
// TcsFacadeImpl), so specs page through GetOperationsByCursor without a gRPC channel.
object TcsOperationsFakes {

  private def unimplemented[A](name: String): IO[A] =
    IO.raiseError(new NotImplementedError(s"$name is not used by the operations fakes"))

  trait FakeOperationsService extends OperationsServiceFs2Grpc[IO, Metadata] {
    def cursorRequests: IO[List[GetOperationsByCursorRequest]]
  }

  // Answers GetOperationsByCursor from `pages`, keyed by the request's cursor, "" for the first
  // page.
  def operationsService(
    pages: Map[String, GetOperationsByCursorResponse]
  ): IO[FakeOperationsService] =
    Ref.of[IO, List[GetOperationsByCursorRequest]](List.empty).map { requests =>
      new FakeOperationsService {
        def getOperationsByCursor(
          request: GetOperationsByCursorRequest,
          ctx: Metadata
        ): IO[GetOperationsByCursorResponse] =
          requests.update(_ :+ request) >> IO.fromOption(pages.get(request.cursor.getOrElse("")))(
            new NoSuchElementException(s"no fake page at cursor ${request.cursor}")
          )

        def cursorRequests: IO[List[GetOperationsByCursorRequest]] = requests.get

        def getOperations(request: OperationsRequest, ctx: Metadata): IO[OperationsResponse] =
          unimplemented("getOperations")
        def getPortfolio(request: PortfolioRequest, ctx: Metadata): IO[PortfolioResponse] =
          unimplemented("getPortfolio")
        def getPositions(request: PositionsRequest, ctx: Metadata): IO[PositionsResponse] =
          unimplemented("getPositions")
        def getWithdrawLimits(
          request: WithdrawLimitsRequest,
          ctx: Metadata
        ): IO[WithdrawLimitsResponse] = unimplemented("getWithdrawLimits")
        def getBrokerReport(request: BrokerReportRequest, ctx: Metadata): IO[BrokerReportResponse] =
          unimplemented("getBrokerReport")
        def getDividendsForeignIssuer(
          request: GetDividendsForeignIssuerRequest,
          ctx: Metadata
        ): IO[GetDividendsForeignIssuerResponse] = unimplemented("getDividendsForeignIssuer")
      }
    }
}
