package st.btu.trading.pillar.facades

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.google.protobuf.timestamp.Timestamp
import io.grpc.Metadata
import org.specs2.mutable.Specification
import ru.tinkoff.piapi.contract.v1.operations.{GetOperationsByCursorResponse, OperationItem}
import st.btu.trading.pillar.models.tcs
import st.btu.trading.pillar.testkit.TcsOperationsFakes

class TcsFacadeImplSpec extends Specification with CatsEffect {

  private val accountNumber = "2000123456"
  private val since = Timestamp(1709251200)
  private val until = Timestamp(1709337600)

  private def item(id: String): OperationItem = OperationItem(id = id)

  private val twoPages = Map(
    "" -> GetOperationsByCursorResponse(
      hasNext = true,
      nextCursor = "page-2",
      items = Seq(item("a"), item("b"))
    ),
    "page-2" -> GetOperationsByCursorResponse(hasNext = false, items = Seq(item("c")))
  )

  private def allOperationsOver(service: TcsOperationsFakes.FakeOperationsService) =
    TcsFacadeImpl
      .allOperations[IO](service, new Metadata(), tcs.BrokerAccountId(accountNumber), since, until)

  "TcsFacadeImpl" >> {
    "allOperations" >> {
      "collects every page, in order" >> {
        for {
          service <- TcsOperationsFakes.operationsService(twoPages)
          operations <- allOperationsOver(service)
        } yield operations.map(_.id) must_== List("a", "b", "c")
      }

      "starts each page at the cursor the previous one returned" >> {
        for {
          service <- TcsOperationsFakes.operationsService(twoPages)
          _ <- allOperationsOver(service)
          requests <- service.cursorRequests
        } yield requests.map(_.cursor) must_== List(None, Some("page-2"))
      }

      "asks for the account's operations in the period, a full page at a time" >> {
        for {
          service <- TcsOperationsFakes.operationsService(twoPages)
          _ <- allOperationsOver(service)
          requests <- service.cursorRequests
        } yield requests.map(r => (r.accountId, r.from, r.to, r.limit)) must_== List.fill(2)(
          (accountNumber, Some(since), Some(until), Some(1000))
        )
      }
    }
  }
}
