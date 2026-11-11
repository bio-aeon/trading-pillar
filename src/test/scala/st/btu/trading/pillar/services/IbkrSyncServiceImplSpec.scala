package st.btu.trading.pillar.services

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{Clock, IO}
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.ibkr.FlexError
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.models.market.{ClassCode, Ticker}
import st.btu.trading.pillar.testkit.IbkrFakes.*
import st.btu.trading.pillar.testkit.MarketDataFakes.{FakeBrokerInstrumentStorage, delayForIO}
import st.btu.trading.pillar.testkit.{IbkrFakes, MarketDataFakes, McpFakes}
import tofu.logging.{Logging, Logs}

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneId}

class IbkrSyncServiceImplSpec extends Specification with CatsEffect {

  private implicit val logs: Logs[IO, IO] =
    Logs.const[IO, IO](Logging.Make.plain[IO].forService[IbkrSyncService[IO]])

  private val accountId = AccountId(1)
  private val otherAccountId = AccountId(2)
  private val ibkrAccount =
    IbkrAccount(accountId, IbkrAccountId("U1111111"), FlexQueryId("987654"))

  private val newYork = ZoneId.of("America/New_York")

  // 10:00 on 9 November 2026 in New York.
  private val now = Instant.parse("2026-11-09T15:00:00Z")

  // Reads from the first day of this year, in windows of a hundred days.
  private val config = IbkrSyncConfig(
    historyStart = Some(LocalDate.of(2026, 1, 1)),
    rereadDays = 30,
    windowDays = 100,
    statementZone = newYork
  )

  private def day(date: LocalDate): String = DateTimeFormatter.BASIC_ISO_DATE.format(date)

  private def emptyStatement(request: FlexRequest): IO[String] =
    IO.pure(statementXml("U1111111", day(request.from), day(request.to)))

  private val generationFailed =
    FlexError.RetriesExhausted(
      FlexError.StatementNotGenerated,
      "Statement could not be generated.",
      10
    )

  private final case class Fixture(
    storage: FakeIbkrStatementStorage,
    instruments: FakeBrokerInstrumentStorage,
    flex: FakeFlexWebService,
    service: IbkrSyncService[IO]
  ) {
    def shared: IO[Vector[AccountOperation]] =
      storage.operations(accountId, Instant.EPOCH, now.plus(1, ChronoUnit.DAYS))

    def windows: IO[List[(LocalDate, LocalDate)]] =
      flex.requests.map(_.map(request => (request.from, request.to)))
  }

  private def fixture(
    config: IbkrSyncConfig = config,
    stored: List[IbkrStatementRow] = Nil,
    translations: List[AccountOperation] = Nil,
    respond: FlexRequest => IO[String] = emptyStatement,
    at: Instant = now
  ): IO[Fixture] = {
    implicit val clock: Clock[IO] = McpFakes.clockAt(at)
    for {
      storage <- IbkrFakes.statementStorage(stored, translations)
      instruments <- MarketDataFakes.brokerInstrumentStorage()
      flex <- IbkrFakes.flexWebService(respond)
      service <- IbkrSyncServiceImpl.create[IO, IO](ibkrAccount, config, flex, storage, instruments)
    } yield Fixture(storage, instruments, flex, service)
  }

  private def window(from: String, to: String): (LocalDate, LocalDate) =
    (LocalDate.parse(from), LocalDate.parse(to))

  // A purchase of 10 shares of AAPL for 1502.50 dollars, reported on `reportDate` (yyyyMMdd).
  private def tradeElement(
    id: String,
    reportDate: String = "20261105",
    overrides: (String, String)*
  ): String = {
    val attributes = Map(
      "accountId" -> "U1111111",
      "assetCategory" -> "STK",
      "symbol" -> "AAPL",
      "conid" -> "265598",
      "currency" -> "USD",
      "buySell" -> "BUY",
      "quantity" -> "10",
      "tradePrice" -> "150.25",
      "proceeds" -> "-1502.50",
      "transactionID" -> id,
      "reportDate" -> reportDate,
      "dateTime" -> s"$reportDate;093000",
      "levelOfDetail" -> "EXECUTION"
    ) ++ overrides
    element("Trade", attributes.toSeq*)
  }

  private def cashElement(id: String, reportDate: String = "20261105"): String =
    element(
      "CashTransaction",
      "accountId" -> "U1111111",
      "type" -> "Dividends",
      "amount" -> "12.50",
      "currency" -> "USD",
      "transactionID" -> id,
      "reportDate" -> reportDate,
      "dateTime" -> s"$reportDate;202000",
      "levelOfDetail" -> "DETAIL"
    )

  private def respondWith(elements: String*)(request: FlexRequest): IO[String] =
    IO.pure(statementXml("U1111111", day(request.from), day(request.to), elements*))

  private def storedRow(
    section: IbkrSection,
    id: String,
    reportDate: String,
    attributes: (String, String)*
  ): IbkrStatementRow =
    IbkrFakes.row(accountId, section, id, LocalDate.parse(reportDate), attributes*)

  // The row of a trade as the parser keeps it, to store before a sync.
  private def storedTrade(id: String, reportDate: String, overrides: (String, String)*) =
    storedRow(
      IbkrSection.Trade,
      id,
      reportDate,
      (Map(
        "assetCategory" -> "STK",
        "conid" -> "265598",
        "currency" -> "USD",
        "buySell" -> "BUY",
        "quantity" -> "10",
        "proceeds" -> "-1502.50",
        "transactionID" -> id,
        "reportDate" -> reportDate.replace("-", ""),
        "dateTime" -> s"${reportDate.replace("-", "")};093000"
      ) ++ overrides).toSeq*
    )

  private def translationsOf(row: IbkrStatementRow, canceled: Set[IbkrTransactionId] = Set.empty) =
    IbkrRowTranslation.translate(row, newYork, canceled).toOption.get

  "IbkrSyncServiceImpl" >> {
    "sync" >> {

      "the statements asked for" >> {
        "begin on the configured date when nothing is stored, and end the day before today" >> {
          for {
            f <- fixture()
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(
            window("2026-01-01", "2026-04-10"),
            window("2026-04-11", "2026-07-19"),
            window("2026-07-20", "2026-10-27"),
            window("2026-10-28", "2026-11-08")
          )
        }

        "begin on 1 January four calendar years back when no date is configured" >> {
          for {
            f <- fixture(config.copy(historyStart = None, windowDays = 5000))
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(window("2022-01-01", "2026-11-08"))
        }

        "never begin before the earliest date IB keeps, whatever date is configured" >> {
          for {
            f <- fixture(
              config.copy(historyStart = Some(LocalDate.of(2020, 3, 1)), windowDays = 5000)
            )
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(window("2022-01-01", "2026-11-08"))
        }

        "begin on the earliest date IB keeps of the new year, once the year has turned" >> {
          for {
            f <- fixture(
              config.copy(historyStart = None, windowDays = 5000),
              at = Instant.parse("2027-01-02T15:00:00Z")
            )
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(window("2023-01-01", "2027-01-01"))
        }

        "begin the configured number of days before the latest stored report date" >> {
          val stored = List(storedTrade("1001", "2026-11-01"), storedTrade("0999", "2026-10-15"))
          for {
            f <- fixture(config.copy(rereadDays = 30, windowDays = 20), stored)
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(
            window("2026-10-02", "2026-10-21"),
            window("2026-10-22", "2026-11-08")
          )
        }

        "do not begin on the configured date once a row is stored" >> {
          val stored = List(storedTrade("1001", "2026-11-01"))
          for {
            f <- fixture(
              config.copy(historyStart = Some(LocalDate.of(2026, 11, 5)), windowDays = 5000),
              stored
            )
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(window("2026-10-02", "2026-11-08"))
        }

        "ignore the rows stored for another account when they begin" >> {
          val ofOther =
            IbkrFakes.row(otherAccountId, IbkrSection.Trade, "7001", LocalDate.of(2026, 11, 1))
          for {
            f <- fixture(config.copy(windowDays = 5000), List(ofOther))
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(window("2026-01-01", "2026-11-08"))
        }

        "end the day before today in the statement zone, not in UTC" >> {
          // 03:00 UTC on 9 November is still the evening of the 8th in New York.
          for {
            f <- fixture(config.copy(windowDays = 5000), at = Instant.parse("2026-11-09T03:00:00Z"))
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(window("2026-01-01", "2026-11-07"))
        }

        "are none when the day to begin on is after the day to end on" >> {
          val stored = List(storedTrade("1001", "2026-11-09"))
          for {
            f <- fixture(config.copy(rereadDays = 0), stored)
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must beEmpty
        }

        "are asked for with the query of the account" >> {
          for {
            f <- fixture(config.copy(windowDays = 5000))
            _ <- f.service.sync
            requests <- f.flex.requests
          } yield requests.map(_.queryId) must_== List(FlexQueryId("987654"))
        }
      }

      "a window that IB cannot generate" >> {
        // Five days, 4 to 8 November.
        val fiveDays = config.copy(rereadDays = 0, windowDays = 5)
        val stored = List(storedTrade("1001", "2026-11-04"))

        def generatesUpTo(days: Long)(request: FlexRequest): IO[String] =
          if (ChronoUnit.DAYS.between(request.from, request.to) + 1 > days)
            IO.raiseError(generationFailed)
          else emptyStatement(request)

        "is read as two halves, again halved while IB still cannot generate them" >> {
          for {
            f <- fixture(fiveDays, stored, respond = generatesUpTo(2))
            _ <- f.service.sync
            windows <- f.windows
          } yield windows must_== List(
            window("2026-11-04", "2026-11-08"),
            window("2026-11-04", "2026-11-06"),
            window("2026-11-04", "2026-11-05"),
            window("2026-11-06", "2026-11-06"),
            window("2026-11-07", "2026-11-08")
          )
        }

        "fails the sync when a window of a single day cannot be generated either" >> {
          for {
            f <- fixture(fiveDays, stored, respond = generatesUpTo(0))
            failure <- f.service.sync.attempt
            windows <- f.windows
          } yield {
            failure must beLeft[Throwable](generationFailed)
            windows must_== List(
              window("2026-11-04", "2026-11-08"),
              window("2026-11-04", "2026-11-06"),
              window("2026-11-04", "2026-11-05"),
              window("2026-11-04", "2026-11-04")
            )
          }
        }

        "is not read in halves for a failure other than 1001" >> {
          val expired = FlexError.Permanent(1012, "Token has expired.")
          for {
            f <- fixture(fiveDays, stored, respond = _ => IO.raiseError(expired))
            failure <- f.service.sync.attempt
            windows <- f.windows
          } yield {
            failure must beLeft[Throwable](expired)
            windows must_== List(window("2026-11-04", "2026-11-08"))
          }
        }

        "keeps what the windows read before it recorded" >> {
          val twoWindows = config.copy(rereadDays = 0, windowDays = 2)
          def respond(request: FlexRequest): IO[String] =
            if (request.from == LocalDate.of(2026, 11, 6)) IO.raiseError(generationFailed)
            else respondWith(cashElement("2001", "20261104"))(request)
          for {
            f <- fixture(twoWindows, stored, respond = respond)
            failure <- f.service.sync.attempt
            rows <- f.storage.storedRows
          } yield {
            failure must beLeft[Throwable]
            rows.map(_.transactionId) must contain(IbkrTransactionId("2001"))
          }
        }
      }

      "the statement of the account" >> {
        "is picked among those a response holds for several accounts" >> {
          val twoAccounts = (_: FlexRequest) =>
            IO.pure(s"""<FlexQueryResponse queryName="Activity" type="AF"><FlexStatements count="2">
                 |<FlexStatement accountId="U2222222" fromDate="20260101" toDate="20261108">
                 |${tradeElement("9001")}</FlexStatement>
                 |<FlexStatement accountId="U1111111" fromDate="20260101" toDate="20261108">
                 |${tradeElement("1001")}</FlexStatement>
                 |</FlexStatements></FlexQueryResponse>""".stripMargin)
          for {
            f <- fixture(config.copy(windowDays = 5000), respond = twoAccounts)
            _ <- f.service.sync
            rows <- f.storage.storedRows
          } yield rows.map(_.transactionId) must_== List(IbkrTransactionId("1001"))
        }

        "must be for the configured IB account" >> {
          val anotherAccount = (request: FlexRequest) =>
            IO.pure(
              statementXml("U9999999", day(request.from), day(request.to), tradeElement("1001"))
            )
          for {
            f <- fixture(config.copy(windowDays = 5000), respond = anotherAccount)
            failure <- f.service.sync.attempt
            rows <- f.storage.storedRows
          } yield {
            failure must beLeft[Throwable](
              IbkrStatementError
                .AccountNotInStatement(IbkrAccountId("U1111111"), List(IbkrAccountId("U9999999")))
            )
            rows must beEmpty
          }
        }

        "must be there at all" >> {
          val noStatements = (_: FlexRequest) =>
            IO.pure("""<FlexQueryResponse><FlexStatements count="0"/></FlexQueryResponse>""")
          for {
            f <- fixture(config.copy(windowDays = 5000), respond = noStatements)
            failure <- f.service.sync.attempt
          } yield failure must beLeft[Throwable](
            IbkrStatementError.AccountNotInStatement(IbkrAccountId("U1111111"), Nil)
          )
        }

        "must be XML of an Activity Flex Query" >> {
          for {
            f <- fixture(respond = _ => IO.pure("not xml"))
            failure <- f.service.sync.attempt
          } yield failure must beLeft[Throwable].like { case _: IbkrStatementError.Malformed => ok }
        }
      }

      "the open positions it returns" >> {
        val oneWindow = config.copy(windowDays = 5000)

        def openPosition(quantity: String): String =
          element(
            "OpenPosition",
            "assetCategory" -> "STK",
            "symbol" -> "AAPL",
            "conid" -> "265598",
            "position" -> quantity,
            "levelOfDetail" -> "SUMMARY"
          )

        def apple(quantity: BigDecimal): IbkrOpenPosition =
          IbkrOpenPosition(
            BrokerInstrumentId("265598"),
            Some(Ticker("AAPL")),
            Some("STK"),
            quantity
          )

        // The statement of a window holds a position of the number of shares given for the day the
        // window begins on.
        def respondByStart(shares: Map[String, String])(request: FlexRequest): IO[String] =
          respondWith(openPosition(shares(day(request.from))))(request)

        "are dated by the last day the statement says it covers, when IB ends it before the window" >> {
          // The window asks up to 8 November, and IB's statement ends on the 6th.
          def endingEarly(request: FlexRequest): IO[String] =
            IO.pure(statementXml("U1111111", day(request.from), "20261106", openPosition("10")))
          for {
            f <- fixture(oneWindow, respond = endingEarly)
            reported <- f.service.sync
          } yield reported must beSome(
            IbkrOpenPositions(LocalDate.of(2026, 11, 6), newYork, List(apple(10)))
          )
        }

        "are those of the statement, as of the last day it covers, in the statement zone" >> {
          for {
            f <- fixture(oneWindow, respond = respondWith(openPosition("10")))
            reported <- f.service.sync
          } yield reported must beSome(
            IbkrOpenPositions(LocalDate.of(2026, 11, 8), newYork, List(apple(10)))
          )
        }

        "are those of the last of several windows" >> {
          val shares =
            Map("20260101" -> "1", "20260411" -> "2", "20260720" -> "3", "20261028" -> "4")
          for {
            f <- fixture(respond = respondByStart(shares))
            reported <- f.service.sync
          } yield reported must beSome(
            IbkrOpenPositions(LocalDate.of(2026, 11, 8), newYork, List(apple(4)))
          )
        }

        "are those of the second half when the last window is read in halves" >> {
          // Five days, 4 to 8 November, which IB generates in pieces of two days at most.
          val fiveDays = config.copy(rereadDays = 0, windowDays = 5)
          val shares = Map("20261104" -> "1", "20261106" -> "2", "20261107" -> "3")
          def respond(request: FlexRequest): IO[String] =
            if (ChronoUnit.DAYS.between(request.from, request.to) + 1 > 2)
              IO.raiseError(generationFailed)
            else respondByStart(shares)(request)
          for {
            f <- fixture(fiveDays, List(storedTrade("1001", "2026-11-04")), respond = respond)
            reported <- f.service.sync
          } yield reported must beSome(
            IbkrOpenPositions(LocalDate.of(2026, 11, 8), newYork, List(apple(3)))
          )
        }

        "are none when no window was read" >> {
          for {
            f <- fixture(config.copy(rereadDays = 0), List(storedTrade("1001", "2026-11-09")))
            reported <- f.service.sync
          } yield reported must beNone
        }
      }

      "recording" >> {
        val oneWindow = config.copy(windowDays = 5000)

        "stores each row of the statement together with its translation" >> {
          val elements = List(tradeElement("1001"), cashElement("2001"))
          for {
            f <- fixture(oneWindow, respond = respondWith(elements*))
            _ <- f.service.sync
            rows <- f.storage.storedRows
            shared <- f.shared
          } yield {
            rows.map(row => (row.accountId, row.section, row.transactionId, row.reportDate)) must_==
              List(
                (
                  accountId,
                  IbkrSection.Trade,
                  IbkrTransactionId("1001"),
                  LocalDate.of(2026, 11, 5)
                ),
                (
                  accountId,
                  IbkrSection.CashTransaction,
                  IbkrTransactionId("2001"),
                  LocalDate.of(2026, 11, 5)
                )
              )
            shared.map(op => (op.ref, op.kind, op.state)) must_== Vector(
              (OperationRef("trade/1001"), OperationKind.Buy, OperationState.Executed),
              (
                OperationRef("cash_transaction/2001"),
                OperationKind.Dividend,
                OperationState.Executed
              )
            )
          }
        }

        "keeps every attribute of a stored row as IB sent it" >> {
          for {
            f <- fixture(
              oneWindow,
              respond = respondWith(tradeElement("1001", "20261105", "extra" -> "kept"))
            )
            _ <- f.service.sync
            rows <- f.storage.storedRows
          } yield rows.map(_.attributes.get("extra")) must_== List(Some("kept"))
        }

        "stores a row that the statement lists twice once" >> {
          for {
            f <- fixture(
              oneWindow,
              respond = respondWith(tradeElement("1001"), tradeElement("1001"))
            )
            _ <- f.service.sync
            sizes <- f.storage.recordedBatchSizes
            rows <- f.storage.storedRows
          } yield {
            sizes must_== List(1)
            rows.size must_== 1
          }
        }

        "replaces a stored row that IB reports again, and its translation" >> {
          val stale = storedTrade("1001", "2026-11-05", "quantity" -> "8")
          for {
            f <- fixture(
              oneWindow,
              List(stale),
              translationsOf(stale),
              respondWith(tradeElement("1001"))
            )
            _ <- f.service.sync
            rows <- f.storage.storedRows
            shared <- f.shared
          } yield {
            rows.map(_.attribute("quantity")) must_== List(Some("10"))
            shared.map(_.quantity) must_== Vector(BigDecimal(10))
          }
        }

        "replaces the translations of a row that IB reports again as a whole, dropping one it no longer has" >> {
          val charged = storedTrade("1001", "2026-11-05", "ibCommission" -> "-1")
          for {
            f <- fixture(
              oneWindow,
              List(charged),
              translationsOf(charged),
              respondWith(tradeElement("1001"))
            )
            before <- f.shared
            _ <- f.service.sync
            after <- f.shared
          } yield {
            before.map(_.ref) must_== Vector(
              OperationRef("trade/1001"),
              OperationRef("trade/1001/commission")
            )
            after.map(_.ref) must_== Vector(OperationRef("trade/1001"))
          }
        }

        "stores at most 500 rows in one call" >> {
          val many = (1 to 1001).map(n => cashElement(s"c$n")).toList
          for {
            f <- fixture(oneWindow, respond = respondWith(many*))
            _ <- f.service.sync
            sizes <- f.storage.recordedBatchSizes
          } yield sizes must_== List(500, 500, 1)
        }

        "stores nothing of a window with a row that cannot be translated" >> {
          val noProceeds = tradeElement("1001", "20261105", "proceeds" -> "-")
          for {
            f <- fixture(oneWindow, respond = respondWith(cashElement("2001"), noProceeds))
            failure <- f.service.sync.attempt
            rows <- f.storage.storedRows
          } yield {
            failure must beLeft[Throwable](
              IbkrStatementError
                .MissingAttribute(IbkrSection.Trade, Some(IbkrTransactionId("1001")), "proceeds")
            )
            rows must beEmpty
          }
        }

        "dates the operations of a row by the time in the statement zone" >> {
          for {
            f <- fixture(oneWindow, respond = respondWith(tradeElement("1001", "20261105")))
            _ <- f.service.sync
            shared <- f.shared
          } yield shared.map(_.occurredAt) must_== Vector(Instant.parse("2026-11-05T14:30:00Z"))
        }
      }

      "cancellations" >> {
        val oneWindow = config.copy(windowDays = 5000)
        val cancel = tradeElement(
          "1002",
          "20261106",
          "transactionType" -> "TradeCancel",
          "notes" -> "Ca",
          "origTransactionID" -> "1001",
          "buySell" -> "SELL (Ca.)",
          "quantity" -> "-10",
          "proceeds" -> "1502.50"
        )

        "cancel both the trade and the cancelling row when one statement holds them" >> {
          for {
            f <- fixture(oneWindow, respond = respondWith(tradeElement("1001"), cancel))
            _ <- f.service.sync
            shared <- f.shared
          } yield shared.map(op => (op.ref, op.state)) must_== Vector(
            (OperationRef("trade/1001"), OperationState.Canceled),
            (OperationRef("trade/1002"), OperationState.Canceled)
          )
        }

        "record a trade again, canceled, when the cancelling row arrives in a later statement" >> {
          val original = storedTrade("1001", "2026-11-05", "ibCommission" -> "-1")
          for {
            f <- fixture(oneWindow, List(original), translationsOf(original), respondWith(cancel))
            before <- f.shared
            _ <- f.service.sync
            after <- f.shared
            rows <- f.storage.storedRows
          } yield {
            before.map(_.state).distinct must_== Vector(OperationState.Executed)
            after.map(op => (op.ref, op.state)) must_== Vector(
              (OperationRef("trade/1001"), OperationState.Canceled),
              (OperationRef("trade/1001/commission"), OperationState.Canceled),
              (OperationRef("trade/1002"), OperationState.Canceled)
            )
            rows.find(_.transactionId == IbkrTransactionId("1001")) must beSome(original)
          }
        }

        "leave a trade the cancelling row does not name as it is" >> {
          val bystander = storedTrade("1003", "2026-11-05")
          for {
            f <- fixture(oneWindow, List(bystander), translationsOf(bystander), respondWith(cancel))
            _ <- f.service.sync
            shared <- f.shared
          } yield shared.filter(_.ref == OperationRef("trade/1003")).map(_.state) must_==
            Vector(OperationState.Executed)
        }
      }

      "instruments" >> {
        val oneWindow = config.copy(windowDays = 5000)

        "are recorded from the Financial Instrument Information of the statement" >> {
          val security = element(
            "SecurityInfo",
            "assetCategory" -> "STK",
            "symbol" -> "AAPL",
            "description" -> "APPLE INC",
            "conid" -> "265598",
            "listingExchange" -> "NASDAQ",
            "currency" -> "USD"
          )
          for {
            f <- fixture(oneWindow, respond = respondWith(security))
            _ <- f.service.sync
            stored <- f.instruments.all
          } yield stored.map(i => (i.instrument, i.ticker, i.classCode, i.name, i.currency)) must_==
            List(
              (
                IbkrFakes.instrumentKey("265598"),
                Ticker("AAPL"),
                Some(ClassCode("NASDAQ")),
                "APPLE INC",
                Currency("USD")
              )
            )
        }

        "take what an entry lacks from the trades of the statement" >> {
          val security = element(
            "SecurityInfo",
            "assetCategory" -> "STK",
            "symbol" -> "AAPL",
            "conid" -> "265598",
            "currency" -> "-"
          )
          for {
            f <- fixture(oneWindow, respond = respondWith(security, tradeElement("1001")))
            _ <- f.service.sync
            stored <- f.instruments.all
          } yield stored.map(i => (i.instrument, i.currency)) must_==
            List((IbkrFakes.instrumentKey("265598"), Currency("USD")))
        }

        "are dated by the time of the sync" >> {
          for {
            f <- fixture(oneWindow, respond = respondWith(tradeElement("1001")))
            _ <- f.service.sync
            stored <- f.instruments.all
          } yield stored.map(_.updatedAt.toInstant) must_== List(now)
        }
      }
    }

    "rebuildTranslations" >> {
      val deposit = storedRow(
        IbkrSection.CashTransaction,
        "2001",
        "2026-11-05",
        "type" -> "Deposits/Withdrawals",
        "amount" -> "1000",
        "currency" -> "USD",
        "dateTime" -> "20261105;100000"
      )

      "records the translation of every row stored for the account" >> {
        val trade = storedTrade("1001", "2026-11-05")
        val ofOther = IbkrFakes.row(
          otherAccountId,
          IbkrSection.CashTransaction,
          "7001",
          LocalDate.of(2026, 11, 5),
          "type" -> "Dividends",
          "amount" -> "5",
          "currency" -> "USD"
        )
        for {
          f <- fixture(stored = List(trade, deposit, ofOther))
          _ <- f.service.rebuildTranslations
          shared <- f.shared
          ofOtherAccount <- f.storage.operations(otherAccountId, Instant.EPOCH, now)
        } yield {
          shared.map(_.ref) must_== Vector(
            OperationRef("trade/1001"),
            OperationRef("cash_transaction/2001")
          )
          ofOtherAccount must beEmpty
        }
      }

      "replaces a stored translation that no longer matches its row" >> {
        val stale = translationsOf(deposit).map(_.copy(kind = OperationKind.Other))
        for {
          f <- fixture(stored = List(deposit), translations = stale)
          _ <- f.service.rebuildTranslations
          shared <- f.shared
        } yield shared.map(_.kind) must_== Vector(OperationKind.Deposit)
      }

      "cancels a trade that a stored cancelling row names, whenever each arrived" >> {
        val original = storedTrade("1001", "2026-11-05")
        val cancel = storedTrade(
          "1002",
          "2026-11-06",
          "transactionType" -> "TradeCancel",
          "origTransactionID" -> "1001",
          "quantity" -> "-10",
          "proceeds" -> "1502.50"
        )
        for {
          f <- fixture(stored = List(original, cancel), translations = translationsOf(original))
          _ <- f.service.rebuildTranslations
          shared <- f.shared
        } yield shared.map(op => (op.ref, op.state)) must_== Vector(
          (OperationRef("trade/1001"), OperationState.Canceled),
          (OperationRef("trade/1002"), OperationState.Canceled)
        )
      }

      "removes a translation that its row no longer produces" >> {
        val bare = storedTrade("1001", "2026-11-05")
        val stale = translationsOf(storedTrade("1001", "2026-11-05", "ibCommission" -> "-1"))
        for {
          f <- fixture(stored = List(bare), translations = stale)
          _ <- f.service.rebuildTranslations
          shared <- f.shared
        } yield shared.map(_.ref) must_== Vector(OperationRef("trade/1001"))
      }

      "leaves the stored rows as they are" >> {
        for {
          f <- fixture(stored = List(deposit))
          before <- f.storage.storedRows
          _ <- f.service.rebuildTranslations
          after <- f.storage.storedRows
        } yield after must_== before
      }

      "records the stored rows 500 at a time" >> {
        val rows = (1 to 1001).toList.map(n =>
          storedRow(
            IbkrSection.CashTransaction,
            s"c$n",
            "2026-11-05",
            "type" -> "Dividends",
            "amount" -> "1",
            "currency" -> "USD"
          )
        )
        for {
          f <- fixture(stored = rows)
          _ <- f.service.rebuildTranslations
          sizes <- f.storage.recordedBatchSizes
        } yield sizes must_== List(500, 500, 1)
      }

      "fails, recording nothing, when a stored row cannot be translated" >> {
        val broken =
          storedRow(IbkrSection.CashTransaction, "2002", "2026-11-05", "type" -> "Dividends")
        for {
          f <- fixture(stored = List(deposit, broken))
          failure <- f.service.rebuildTranslations.attempt
          shared <- f.shared
        } yield {
          failure must beLeft[Throwable].like { case _: IbkrStatementError.MissingAttribute => ok }
          shared must beEmpty
        }
      }
    }
  }
}
