package st.btu.trading.pillar.services

import cats.effect.{IO, Ref}
import cats.effect.testing.specs2.CatsEffect
import com.google.protobuf.timestamp.Timestamp
import org.specs2.mutable.Specification
import ru.tinkoff.piapi.contract.v1.common.MoneyValue
import ru.tinkoff.piapi.contract.v1.operations.{OperationItem, OperationState, OperationType}
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.facades.TcsFacade
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes, MarketDataFakes}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import tofu.WithLocal
import tofu.logging.{Logging, Logs}

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class TcsPortfolioServiceImplSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[TcsPortfolioService[IO]])

  private val accountId = AccountId(1)
  private val boughtAt = Instant.parse("2024-03-01T10:15:30Z")
  private val boughtAtUtc = ZonedDateTime.ofInstant(boughtAt, ZoneOffset.UTC)

  private val buy = OperationItem(
    id = "op-1",
    `type` = OperationType.OPERATION_TYPE_BUY,
    state = OperationState.OPERATION_STATE_EXECUTED,
    figi = "BBG000B9XRY4",
    instrumentType = "share",
    payment = Some(MoneyValue(currency = "usd", units = -1001, nano = -500000000)),
    price = Some(MoneyValue(currency = "usd", units = 100, nano = 150000000)),
    quantity = 10,
    quantityRest = 0,
    date = Some(Timestamp(boughtAt.getEpochSecond, boughtAt.getNano))
  )

  // T-Invest gives a commission its parent operation's id.
  private val buyFee = buy.copy(
    `type` = OperationType.OPERATION_TYPE_BROKER_FEE,
    payment = Some(MoneyValue(currency = "usd", units = -3)),
    quantity = 0
  )

  private def syncOver(
    stored: List[TcsPortfolioOperation],
    fetched: List[OperationItem]
  ): IO[AnalyticsFakes.FakeTcsPortfolioOperationStorage] =
    for {
      storage <- AnalyticsFakes.tcsPortfolioOperationStorage(stored)
      facade <- Ref.of[IO, Portfolio](Portfolio(List.empty)).map(Fakes.tcsFacade(_, fetched))
      withLocal <- MarketDataFakes.withLocal(ProcessContext("trace", accountId))
      service <- {
        implicit val context: WithLocal[IO, ProcessContext] = withLocal
        TcsPortfolioServiceImpl.create[IO, IO](1, storage, facade)
      }
      _ <- service.syncOperations(interval = 1.day, overlap = 1.hour)
    } yield storage

  "TcsPortfolioServiceImpl" >> {
    "syncOperations" >> {
      "stores a fetched operation, its currency taken from the payment" >> {
        for {
          storage <- syncOver(Nil, List(buy))
          saved <- storage.lastOperation(accountId)
        } yield saved must beSome[TcsPortfolioOperation].like { case op =>
          op.extId must_== PortfolioOperationExtId("op-1")
          op.operationType must_== PortfolioOperationType("OPERATION_TYPE_BUY")
          op.status must_== PortfolioOperationStatus("OPERATION_STATE_EXECUTED")
          op.figi must beSome(Figi("BBG000B9XRY4"))
          op.instrumentType must beSome(PortfolioInstrumentType("share"))
          op.currency must_== Currency("USD")
          op.price must_== BigDecimal("100.15")
          op.amount must_== BigDecimal("-1001.5")
          op.quantity must_== 10
          op.quantityRest must_== 0
          op.createdAt.toInstant must_== boughtAt
          op.protocolVersion must_== ProtocolVersion(TcsFacade.ApiVersion)
          op.accountId must_== accountId
        }
      }

      "keeps an operation already stored and adds its commission, which shares its id" >> {
        val stored = AnalyticsFakes
          .operation(accountId, "OPERATION_TYPE_BUY", BigDecimal("-1001.5"), boughtAtUtc)
          .copy(extId = PortfolioOperationExtId(buy.id))
        for {
          storage <- syncOver(List(stored), List(buy, buyFee))
          all <- storage.operationsByDtRange(
            accountId,
            boughtAtUtc.minusDays(1),
            boughtAtUtc.plusDays(1)
          )
        } yield all.map(op => (op.operationType, op.extId)) must_== Set(
          (PortfolioOperationType("OPERATION_TYPE_BUY"), PortfolioOperationExtId("op-1")),
          (PortfolioOperationType("OPERATION_TYPE_BROKER_FEE"), PortfolioOperationExtId("op-1"))
        )
      }
    }
  }
}
