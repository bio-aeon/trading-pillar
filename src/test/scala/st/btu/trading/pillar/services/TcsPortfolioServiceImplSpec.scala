package st.btu.trading.pillar.services

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{Clock, IO}
import com.google.protobuf.timestamp.Timestamp
import org.specs2.mutable.Specification
import ru.tinkoff.piapi.contract.v1.common.MoneyValue
import ru.tinkoff.piapi.contract.v1.operations.{
  OperationItem,
  OperationState as ContractState,
  OperationType as ContractType
}
import st.btu.trading.pillar.data.ProcessContext
import st.btu.trading.pillar.facades.TcsFacade
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.testkit.Fakes.{FakeTcsFacade, RequestedWindow}
import st.btu.trading.pillar.testkit.AnalyticsFakes.FakeTcsPortfolioOperationStorage
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes, MarketDataFakes, McpFakes}
import tofu.WithLocal
import tofu.logging.{Logging, Logs}

import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import scala.concurrent.duration.*

class TcsPortfolioServiceImplSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[TcsPortfolioService[IO]])

  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)

  private val now = Instant.parse("2026-11-05T12:00:00Z")
  private implicit val clock: Clock[IO] = McpFakes.clockAt(now)

  private val config = TcsPortfolioSyncConfig(initialYearsAgo = 1, rereadWindow = 7.days)

  // The point a sync that has caught up reached on its last call.
  private val caughtUp = utc(now.minus(10, ChronoUnit.MINUTES))

  private val boughtAt = Instant.parse("2026-11-05T10:15:30Z")

  private def utc(instant: Instant): ZonedDateTime =
    ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)

  private def timestamp(instant: Instant): Timestamp =
    Timestamp(instant.getEpochSecond, instant.getNano)

  private val buy = OperationItem(
    id = "op-1",
    `type` = ContractType.OPERATION_TYPE_BUY,
    state = ContractState.OPERATION_STATE_EXECUTED,
    figi = "BBG000B9XRY4",
    instrumentType = "share",
    payment = Some(MoneyValue(currency = "usd", units = -1001, nano = -500000000)),
    price = Some(MoneyValue(currency = "usd", units = 100, nano = 150000000)),
    quantity = 10,
    quantityRest = 0,
    date = Some(timestamp(boughtAt))
  )

  private val buyFee = buy.copy(
    `type` = ContractType.OPERATION_TYPE_BROKER_FEE,
    payment = Some(MoneyValue(currency = "usd", units = -3)),
    quantity = 0
  )

  private def dated(item: OperationItem, id: String, at: Instant): OperationItem =
    item.copy(id = id, date = Some(timestamp(at)))

  // The buy as stored while it was still in progress, with 4 of its 10 units unfilled.
  private val storedInProgress: TcsPortfolioOperation =
    AnalyticsFakes
      .operation(
        accountId,
        "OPERATION_TYPE_BUY",
        BigDecimal("-1001.5"),
        utc(boughtAt),
        Some("BBG000B9XRY4"),
        quantity = 10,
        status = "OPERATION_STATE_PROGRESS"
      )
      .copy(extId = PortfolioOperationExtId("op-1"), quantityRest = 4)

  private final case class Fixture(
    storage: FakeTcsPortfolioOperationStorage,
    facade: FakeTcsFacade,
    service: TcsPortfolioService[IO]
  ) {

    // One call of the sync, which by default continues from a sync that has caught up.
    def sync(lastDt: Option[ZonedDateTime] = Some(caughtUp)): IO[ZonedDateTime] =
      service.syncOperations(interval = 1.day, overlap = 1.hour, lastDt)

    def shared: IO[Vector[AccountOperation]] =
      storage.operations(accountId, Instant.EPOCH, now.plus(1, ChronoUnit.DAYS))
  }

  private def fixture(
    stored: List[TcsPortfolioOperation] = Nil,
    translations: List[AccountOperation] = Nil,
    reported: List[OperationItem] = Nil
  ): IO[Fixture] =
    for {
      storage <- AnalyticsFakes.tcsPortfolioOperationStorage(stored, translations)
      facade <- Fakes.tcsFacade(reported)
      withLocal <- MarketDataFakes.withLocal(ProcessContext("trace", accountId))
      service <- {
        implicit val context: WithLocal[IO, ProcessContext] = withLocal
        TcsPortfolioServiceImpl.create[IO, IO](config, storage, facade)
      }
    } yield Fixture(storage, facade, service)

  private def window(since: String, until: String): RequestedWindow =
    RequestedWindow(Instant.parse(since), Instant.parse(until))

  "TcsPortfolioServiceImpl" >> {
    "syncOperations" >> {
      "records a fetched operation as received, its currency taken from the payment" >> {
        for {
          f <- fixture(reported = List(buy))
          _ <- f.sync()
          saved <- f.storage.lastOperation(accountId)
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

      "records the translation of a fetched operation together with it" >> {
        for {
          f <- fixture(reported = List(buy))
          _ <- f.sync()
          shared <- f.shared
        } yield shared.map(op => (op.ref, op.kind, op.state, op.quantity)) must_== Vector(
          (
            OperationRef("op-1/OPERATION_TYPE_BUY"),
            OperationKind.Buy,
            OperationState.Executed,
            BigDecimal(10)
          )
        )
      }

      "records two operations of different types under one id as two operations" >> {
        for {
          f <- fixture(reported = List(buy, buyFee))
          _ <- f.sync()
          stored <- f.storage.operationsByDtRange(
            accountId,
            utc(boughtAt.minusSeconds(3600)),
            utc(now)
          )
          shared <- f.shared
        } yield {
          stored.map(op => (op.operationType, op.extId)) must_== Set(
            (PortfolioOperationType("OPERATION_TYPE_BUY"), PortfolioOperationExtId("op-1")),
            (PortfolioOperationType("OPERATION_TYPE_BROKER_FEE"), PortfolioOperationExtId("op-1"))
          )
          shared.map(_.ref).toSet must_== Set(
            OperationRef("op-1/OPERATION_TYPE_BUY"),
            OperationRef("op-1/OPERATION_TYPE_BROKER_FEE")
          )
        }
      }

      "replaces an operation stored in progress with the state Tinkoff reports for it now" >> {
        for {
          f <- fixture(
            stored = List(storedInProgress),
            translations = List(TcsOperationTranslation.translate(storedInProgress)),
            reported = List(buy)
          )
          _ <- f.sync()
          stored <- f.storage.operationsByDtRange(
            accountId,
            utc(boughtAt.minusSeconds(3600)),
            utc(now)
          )
          shared <- f.shared
        } yield {
          stored.map(op => (op.status, op.quantityRest)) must_== Set(
            (PortfolioOperationStatus("OPERATION_STATE_EXECUTED"), 0)
          )
          shared.map(op => (op.state, op.quantity)) must_== Vector(
            (OperationState.Executed, BigDecimal(10))
          )
        }
      }

      "the window of operations asked for" >> {
        "reaches back over the re-read window once the sync has caught up" >> {
          for {
            f <- fixture()
            _ <- f.sync()
            requests <- f.facade.requests
          } yield requests must_== List(window("2026-10-29T12:00:00Z", "2026-11-05T13:00:00Z"))
        }

        "begins an overlap before the point reached while the sync is still catching up" >> {
          for {
            f <- fixture()
            _ <- f.sync(Some(utc(Instant.parse("2026-08-01T00:00:00Z"))))
            requests <- f.facade.requests
          } yield requests must_== List(window("2026-07-31T23:00:00Z", "2026-08-02T01:00:00Z"))
        }

        "begins an overlap before the newest stored operation when no point is given" >> {
          val newest = AnalyticsFakes.operation(
            accountId,
            "OPERATION_TYPE_INPUT",
            BigDecimal(100),
            utc(Instant.parse("2026-09-01T08:00:00Z"))
          )
          for {
            f <- fixture(stored = List(newest))
            _ <- f.sync(None)
            requests <- f.facade.requests
          } yield requests must_== List(window("2026-09-01T07:00:00Z", "2026-09-02T09:00:00Z"))
        }

        "begins the configured number of years back when no operation is stored" >> {
          for {
            f <- fixture()
            _ <- f.sync(None)
            requests <- f.facade.requests
          } yield requests must_== List(window("2025-11-05T11:00:00Z", "2025-11-06T13:00:00Z"))
        }
      }

      "the point reached" >> {
        "is the end of the window once the sync has caught up" >> {
          for {
            f <- fixture()
            reached <- f.sync()
          } yield reached.toInstant must_== now
        }

        "is still the end of the window when the window held operations dated before it" >> {
          for {
            f <- fixture(reported = List(buy))
            reached <- f.sync()
          } yield reached.toInstant must_== now
        }

        "is the length of the window past the point it began from while still catching up" >> {
          for {
            f <- fixture()
            reached <- f.sync(Some(utc(Instant.parse("2026-08-01T00:00:00Z"))))
          } yield reached.toInstant must_== Instant.parse("2026-08-02T00:00:00Z")
        }
      }

      "an operation dated before the point reached" >> {
        "is stored when Tinkoff reports it only later, within the re-read window" >> {
          val late = dated(buy, "op-late", now.minus(3, ChronoUnit.DAYS))
          for {
            f <- fixture()
            reached <- f.sync()
            _ <- f.facade.report(List(late))
            _ <- f.sync(Some(reached))
            stored <- f.storage.operationsByDtRange(
              accountId,
              utc(now.minus(7, ChronoUnit.DAYS)),
              utc(now)
            )
          } yield stored.map(_.extId) must_== Set(PortfolioOperationExtId("op-late"))
        }

        "is not fetched again once it is older than the re-read window" >> {
          val tooOld = dated(buy, "op-old", now.minus(8, ChronoUnit.DAYS))
          for {
            f <- fixture(reported = List(tooOld))
            _ <- f.sync()
            shared <- f.shared
          } yield shared must beEmpty
        }
      }
    }

    "rebuildTranslations" >> {
      "records the translation of every operation stored for the account" >> {
        val first = AnalyticsFakes.operation(accountId, "OPERATION_TYPE_INPUT", 100, utc(boughtAt))
        val second =
          AnalyticsFakes.operation(accountId, "OPERATION_TYPE_BROKER_FEE", -3, utc(boughtAt))
        val ofAnotherAccount =
          AnalyticsFakes.operation(otherAccountId, "OPERATION_TYPE_INPUT", 5, utc(boughtAt))
        for {
          f <- fixture(stored = List(first, second, ofAnotherAccount))
          _ <- f.service.rebuildTranslations
          shared <- f.shared
          ofOther <- f.storage.operations(otherAccountId, Instant.EPOCH, now)
        } yield {
          shared.map(_.kind).toSet must_== Set[OperationKind](
            OperationKind.Deposit,
            OperationKind.Fee
          )
          ofOther must beEmpty
        }
      }

      "replaces a stored translation that no longer matches its operation" >> {
        val deposit =
          AnalyticsFakes.operation(accountId, "OPERATION_TYPE_INPUT", 100, utc(boughtAt))
        val stale = TcsOperationTranslation.translate(deposit).copy(kind = OperationKind.Other)
        for {
          f <- fixture(stored = List(deposit), translations = List(stale))
          _ <- f.service.rebuildTranslations
          shared <- f.shared
        } yield shared.map(_.kind) must_== Vector(OperationKind.Deposit)
      }

      "leaves the stored operations as they are" >> {
        val deposit =
          AnalyticsFakes.operation(accountId, "OPERATION_TYPE_INPUT", 100, utc(boughtAt))
        for {
          f <- fixture(stored = List(deposit))
          before <- f.storage.operationsByDtRange(accountId, utc(Instant.EPOCH), utc(now))
          _ <- f.service.rebuildTranslations
          after <- f.storage.operationsByDtRange(accountId, utc(Instant.EPOCH), utc(now))
        } yield after must_== before
      }

      "records the stored operations 500 at a time" >> {
        val deposits = (0 until 1001).toList.map { second =>
          AnalyticsFakes.operation(
            accountId,
            "OPERATION_TYPE_INPUT",
            1,
            utc(boughtAt.plusSeconds(second.toLong))
          )
        }
        for {
          f <- fixture(stored = deposits)
          _ <- f.service.rebuildTranslations
          sizes <- f.storage.recordedBatchSizes
        } yield sizes must_== List(500, 500, 1)
      }
    }
  }
}
