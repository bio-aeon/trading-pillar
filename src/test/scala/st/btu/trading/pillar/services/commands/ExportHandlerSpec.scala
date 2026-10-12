package st.btu.trading.pillar.services.commands

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.analytics.portfolio.ValuationPoint
import st.btu.trading.pillar.models.domain.{AccountId, TcsPortfolioOperation}
import st.btu.trading.pillar.models.market.{DividendEvent, Source, Ticker, WatchlistItem}
import st.btu.trading.pillar.models.tg.ChatId
import st.btu.trading.pillar.testkit.{AnalyticsFakes, Fakes, MarketDataFakes}

import java.nio.charset.StandardCharsets
import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

class ExportHandlerSpec extends Specification with CatsEffect {

  private val chatId = ChatId(1L)
  private val accountId = AccountId(1)

  private def handlerWith(
    operations: List[TcsPortfolioOperation] = Nil,
    valuations: Vector[ValuationPoint] = Vector.empty,
    dividends: List[DividendEvent] = Nil,
    watchlist: List[WatchlistItem] = Nil
  ): IO[(ExportHandler[IO], Fakes.FakeTgGateway[IO])] =
    for {
      tgGateway <- Fakes.tgGateway()
      operationsStorage <- AnalyticsFakes.tcsPortfolioOperationStorage(operations)
      valuationsStorage <- AnalyticsFakes.portfolioValuationStorage()
      _ <- valuationsStorage.upsert(accountId, "RUB", valuations)
      dividendStorage <- MarketDataFakes.dividendStorage(dividends)
      watchlistStorage <- MarketDataFakes.watchlistStorage(watchlist)
    } yield (
      new ExportHandler[IO](
        tgGateway,
        operationsStorage,
        valuationsStorage,
        dividendStorage,
        watchlistStorage,
        accountId
      ),
      tgGateway
    )

  private def csvOf(doc: Fakes.SentDocument): String =
    new String(doc.content, StandardCharsets.UTF_8)

  "ExportHandler" >> {
    "handle" >> {

      "exports this year's operations as a CSV document" >> {
        val op2024 = AnalyticsFakes.operation(
          accountId,
          "OPERATION_TYPE_BUY",
          -1000,
          ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC),
          figi = Some("F1"),
          quantity = 10
        )
        val op2023 = AnalyticsFakes.operation(
          accountId,
          "OPERATION_TYPE_SELL",
          1234,
          ZonedDateTime.of(2023, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC),
          figi = Some("F1"),
          quantity = 999
        )
        for {
          pair <- handlerWith(operations = List(op2024, op2023))
          (handler, tgGateway) = pair
          _ <- handler.handle(chatId, List("operations", "2024"))
          docs <- tgGateway.sentDocuments
          messages <- tgGateway.sentMessages
        } yield {
          docs must have size 1
          val doc = docs.head
          doc.filename must_== "operations-2024.csv"
          doc.mimeType must_== "text/csv"
          val csv = csvOf(doc)
          csv must startWith("created_at,operation_type,figi,currency,price,quantity,amount,status")
          csv must contain("OPERATION_TYPE_BUY")
          csv must not(contain("999")) // op2023's marker quantity must not appear
          messages must beEmpty
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
          DividendEvent(Ticker("AAPL"), LocalDate.of(2024, 6, 1), 1.5, Some("USD"), Source.Yahoo)
        val msftDividend =
          DividendEvent(Ticker("MSFT"), LocalDate.of(2024, 6, 1), 2.5, Some("USD"), Source.Yahoo)
        val watchlistItem = WatchlistItem(
          accountId,
          Ticker("AAPL"),
          Source.Yahoo,
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
        val op = AnalyticsFakes.operation(
          accountId,
          "OPERATION_TYPE_BUY",
          -100,
          ZonedDateTime.now(ZoneOffset.UTC),
          figi = Some("F1"),
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
