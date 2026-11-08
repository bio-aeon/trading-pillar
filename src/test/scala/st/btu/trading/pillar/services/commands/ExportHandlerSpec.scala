package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.*
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes, MarketDataFakes}

import java.nio.charset.StandardCharsets
import java.time.{Instant, LocalDate, ZoneOffset, ZonedDateTime}

class ExportHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)
  private val rub = Currency("RUB")
  private val usd = Currency("USD")

  private def handlerWith(
    operations: List[AccountOperation] = Nil,
    valuations: Vector[ValuationPoint] = Vector.empty,
    dividends: List[DividendEvent] = Nil,
    watchlist: List[WatchlistItem] = Nil,
    settings: List[TickerSource] = Nil
  ): IO[(ExportHandler[IO], Fakes.FakeTgGateway[IO])] =
    for {
      tgGateway <- Fakes.tgGateway()
      operationsStorage <- AnalyticsFakes.accountOperationStorage(operations)
      valuationsStorage <- AnalyticsFakes.portfolioValuationStorage()
      _ <- valuationsStorage.upsert(accountId, rub, valuations)
      dividendStorage <- MarketDataFakes.dividendStorage(dividends)
      watchlistStorage <- MarketDataFakes.watchlistStorage(watchlist)
      sources <- MarketDataFakes.marketDataSources(settings = settings)
    } yield (
      new ExportHandler[IO](
        tgGateway,
        operationsStorage,
        valuationsStorage,
        dividendStorage,
        watchlistStorage,
        sources,
        accountId
      ),
      tgGateway
    )

  private def csvOf(doc: Fakes.SentDocument): String =
    new String(doc.content, StandardCharsets.UTF_8)

  // An operation of the account, as it happened at the instant written in `occurredAt`.
  private def accountOperation(
    kind: OperationKind,
    amount: BigDecimal,
    occurredAt: String,
    instrument: Option[String] = None,
    quantity: BigDecimal = 0,
    price: Option[BigDecimal] = None,
    state: OperationState = OperationState.Executed,
    currency: String = "RUB",
    brokerType: String = "OPERATION_TYPE_OTHER"
  ): AccountOperation =
    AnalyticsFakes.accountOperation(
      accountId,
      kind,
      amount,
      Instant.parse(occurredAt),
      instrument,
      quantity,
      currency,
      state,
      price,
      Some(brokerType)
    )

  "ExportHandler" >> {
    "handle" >> {

      "operations" >> {

        // Stored as a numeric column hands them back, with every decimal place of its scale.
        val deposit = accountOperation(
          OperationKind.Deposit,
          BigDecimal("5000.000000000000000000"),
          "2024-02-10T00:00:00Z",
          brokerType = "OPERATION_TYPE_INPUT_SWIFT"
        )
        val executedBuy = accountOperation(
          OperationKind.Buy,
          BigDecimal("-1000.500000000000000000"),
          "2024-03-01T09:30:00Z",
          instrument = Some("BBG004730N88"),
          quantity = BigDecimal("10.000000000000000000"),
          price = Some(BigDecimal("100.050000000000000000")),
          brokerType = "OPERATION_TYPE_BUY"
        )
        val pendingBuy = accountOperation(
          OperationKind.Buy,
          BigDecimal("-0.2"),
          "2024-03-05T10:00:00Z",
          instrument = Some("BBG000B9XRY4"),
          quantity = BigDecimal("0.00000012"),
          price = Some(BigDecimal("1666666.666666666667")),
          state = OperationState.Pending,
          currency = "USD",
          brokerType = "OPERATION_TYPE_BUY"
        )
        val canceledBuy = accountOperation(
          OperationKind.Buy,
          BigDecimal(0),
          "2024-03-06T10:00:00Z",
          instrument = Some("BBG000B9XRY4"),
          state = OperationState.Canceled,
          currency = "USD",
          brokerType = "OPERATION_TYPE_BUY"
        )

        "exports the year's operations of every state, one row each, under the shared columns" >> {
          for {
            pair <- handlerWith(operations = List(canceledBuy, executedBuy, deposit, pendingBuy))
            (handler, tgGateway) = pair
            _ <- handler.handle(chatId, List("operations", "2024"))
            docs <- tgGateway.sentDocuments
            messages <- tgGateway.sentMessages
          } yield {
            docs must have size 1
            val doc = docs.head
            doc.filename must_== "operations-2024.csv"
            doc.mimeType must_== "text/csv"
            csvOf(doc) must_== List(
              "occurred_at,kind,state,broker_type,instrument_broker,instrument_id,quantity,price,amount,currency",
              "2024-02-10T00:00:00Z,deposit,executed,OPERATION_TYPE_INPUT_SWIFT,,,0,,5000,RUB",
              "2024-03-01T09:30:00Z,buy,executed,OPERATION_TYPE_BUY,tinkoff,BBG004730N88,10,100.05,-1000.5,RUB",
              "2024-03-05T10:00:00Z,buy,pending,OPERATION_TYPE_BUY,tinkoff,BBG000B9XRY4,0.00000012,1666666.666666666667,-0.2,USD",
              "2024-03-06T10:00:00Z,buy,canceled,OPERATION_TYPE_BUY,tinkoff,BBG000B9XRY4,0,,0,USD",
              ""
            ).mkString("\r\n")
            messages must beEmpty
          }
        }

        "leaves out operations of other years, from the first instant of the next year on" >> {
          val lastInstantOf2023 =
            accountOperation(OperationKind.Deposit, 111, "2023-12-31T23:59:59Z")
          val firstInstantOf2024 =
            accountOperation(OperationKind.Deposit, 222, "2024-01-01T00:00:00Z")
          val firstInstantOf2025 =
            accountOperation(OperationKind.Deposit, 333, "2025-01-01T00:00:00Z")
          for {
            pair <- handlerWith(operations =
              List(lastInstantOf2023, firstInstantOf2024, firstInstantOf2025)
            )
            (handler, tgGateway) = pair
            _ <- handler.handle(chatId, List("operations", "2024"))
            docs <- tgGateway.sentDocuments
          } yield {
            val csv = csvOf(docs.head)
            csv must contain("222")
            csv must not(contain("111"))
            csv must not(contain("333"))
          }
        }

        "leaves out the operations of other accounts" >> {
          val ofOtherAccount = AnalyticsFakes.accountOperation(
            AccountId(2),
            OperationKind.Deposit,
            999,
            Instant.parse("2024-02-10T00:00:00Z")
          )
          for {
            pair <- handlerWith(operations = List(deposit, ofOtherAccount))
            (handler, tgGateway) = pair
            _ <- handler.handle(chatId, List("operations", "2024"))
            docs <- tgGateway.sentDocuments
          } yield csvOf(docs.head) must not(contain("999"))
        }
      }

      "exports the stored valuation series for the requested year" >> {
        val points = Vector(
          ValuationPoint(LocalDate.of(2024, 1, 15), 1000, 900, 0),
          ValuationPoint(LocalDate.of(2023, 12, 31), 500, 500, 0)
        )
        for {
          pair <- handlerWith(valuations = points)
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("valuations", "2024"))
          docs <- tgGateway.sentDocuments
        } yield {
          val doc = docs.head
          doc.filename must_== "valuations-2024.csv"
          val csv = csvOf(doc)
          csv must contain("2024-01-15")
          csv must not(contain("2023-12-31"))
        }
      }

      "exports dividends of the year for the account's watchlist tickers only" >> {
        val aaplDividend =
          DividendEvent(Ticker("AAPL"), LocalDate.of(2024, 6, 1), 1.5, Some(usd), Source.Yahoo)
        val msftDividend =
          DividendEvent(Ticker("MSFT"), LocalDate.of(2024, 6, 1), 2.5, Some(usd), Source.Yahoo)
        val watchlistItem = WatchlistItem(
          accountId,
          Ticker("AAPL"),
          enabled = true,
          note = None,
          createdAt = ZonedDateTime.now(ZoneOffset.UTC)
        )
        for {
          pair <- handlerWith(
            dividends = List(aaplDividend, msftDividend),
            watchlist = List(watchlistItem)
          )
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("dividends", "2024"))
          docs <- tgGateway.sentDocuments
        } yield {
          val csv = csvOf(docs.head)
          csv must contain("AAPL")
          csv must not(contain("MSFT"))
        }
      }

      "exports each ticker's dividends from the source it is configured to use" >> {
        val aapl = Ticker("AAPL")
        val sber = Ticker("SBER")
        def dividend(ticker: Ticker, amount: BigDecimal, source: Source) =
          DividendEvent(ticker, LocalDate.of(2024, 6, 1), amount, Some(usd), source)
        def watched(ticker: Ticker) =
          WatchlistItem(accountId, ticker, enabled = true, None, ZonedDateTime.now(ZoneOffset.UTC))
        for {
          pair <- handlerWith(
            dividends = List(
              // AAPL has no setting of its own, so it uses the default, Yahoo.
              dividend(aapl, BigDecimal("1.5"), Source.Yahoo),
              dividend(aapl, BigDecimal("111"), Source.Tinkoff),
              // SBER is set to Tinkoff.
              dividend(sber, BigDecimal("2.5"), Source.Tinkoff),
              dividend(sber, BigDecimal("222"), Source.Yahoo)
            ),
            watchlist = List(watched(aapl), watched(sber)),
            settings = List(TickerSource(sber, SourceSetting.Tinkoff(None)))
          )
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("dividends", "2024"))
          docs <- tgGateway.sentDocuments
        } yield {
          val csv = csvOf(docs.head)
          csv must contain("AAPL,2024-06-01,1.5,USD")
          csv must contain("SBER,2024-06-01,2.5,USD")
          csv must not(contain("111"))
          csv must not(contain("222"))
        }
      }

      "replies with a short text instead of a file when there is nothing to export" >> {
        for {
          pair <- handlerWith()
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("operations", "2099"))
          docs <- tgGateway.sentDocuments
          messages <- tgGateway.sentMessages
        } yield {
          docs must beEmpty
          messages.head.message must_== "No operations to export for 2099."
        }
      }

      "defaults to the current year when no year argument is given" >> {
        val thisYear = ZonedDateTime.now(ZoneOffset.UTC).getYear
        val op = AnalyticsFakes.accountOperation(
          accountId,
          OperationKind.Buy,
          -100,
          Instant.now(),
          instrument = Some("F1"),
          quantity = 1
        )
        for {
          pair <- handlerWith(operations = List(op))
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("operations"))
          docs <- tgGateway.sentDocuments
        } yield docs.head.filename must_== s"operations-$thisYear.csv"
      }

      "replies with usage for an unrecognized or missing kind" >> {
        for {
          pair <- handlerWith()
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("bogus"))
          _ <- handler.handle(chatId, Nil)
          messages <- tgGateway.sentMessages
        } yield {
          messages must have size 2
          messages(0).message must contain("Usage")
          messages(1).message must contain("Usage")
        }
      }
    }
  }
}
