package st.btu.trading.pillar.gateways.tcs

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.google.protobuf.timestamp.Timestamp
import io.grpc.Status
import org.specs2.mutable.Specification
import ru.tinkoff.piapi.contract.v1.common.{MoneyValue, Quotation}
import ru.tinkoff.piapi.contract.v1.instruments.{
  Dividend,
  GetDividendsResponse,
  Instrument,
  InstrumentResponse
}
import ru.tinkoff.piapi.contract.v1.marketdata.{
  GetCandlesResponse,
  GetLastPricesResponse,
  HistoricCandle,
  LastPrice
}
import st.btu.trading.pillar.gateways.tcs.TcsMarketDataSource.TcsMarketDataConfig
import st.btu.trading.pillar.models.market.{MarketDataError, Source, Ticker}
import st.btu.trading.pillar.testkit.MarketDataFakes.delayForIO
import st.btu.trading.pillar.testkit.TcsMarketDataFakes
import tofu.logging.{Logging, Logs}

import java.time.{LocalDate, ZoneId, ZonedDateTime}

class TcsMarketDataSourceSpec extends Specification with CatsEffect {

  private val ticker = Ticker("SBER")
  private val figi = "BBG004730N88"
  private val zone = ZoneId.of("Europe/Moscow")
  private val config = TcsMarketDataConfig(classCode = "TQBR", zone = zone)

  private def instrument(
    name: String = "Sberbank",
    instrumentType: String = "share",
    currency: String = "rub",
    exchange: String = "MOEX"
  ): Instrument =
    Instrument(
      figi = figi,
      ticker = ticker.value,
      currency = currency,
      name = name,
      exchange = exchange,
      instrumentType = instrumentType
    )

  private def ts(date: LocalDate): Timestamp = {
    val i = date.atStartOfDay(zone).toInstant
    Timestamp.of(i.getEpochSecond, i.getNano)
  }

  private def candle(
    date: LocalDate,
    close: Quotation = Quotation(102, 500000000),
    isComplete: Boolean = true
  ): HistoricCandle =
    HistoricCandle(
      open = Some(Quotation(100, 0)),
      high = Some(Quotation(105, 0)),
      low = Some(Quotation(99, 0)),
      close = Some(close),
      volume = 1000L,
      time = Some(ts(date)),
      isComplete = isComplete
    )

  private def buildSource(
    instruments: TcsMarketDataFakes.FakeInstrumentsService,
    marketData: TcsMarketDataFakes.FakeMarketDataService
  ): IO[TcsMarketDataSource[IO]] = {
    implicit val logs: Logs[IO, IO] =
      Logs.const[IO, IO](Logging.Make.plain[IO].forService[TcsMarketDataSource[IO]])
    TcsMarketDataSource.create[IO, IO](instruments, marketData, config)
  }

  private def workingInstruments: IO[TcsMarketDataFakes.FakeInstrumentsService] =
    TcsMarketDataFakes.instrumentsService(
      Map(ticker.value -> IO.pure(InstrumentResponse(instrument = Some(instrument()))))
    )

  "TcsMarketDataSource" >> {
    "dailyBars" >> {

      "scales a units-and-nanos quote to a 6-decimal price" >> {
        val day = LocalDate.of(2024, 1, 10)
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService(candles =
            Map(
              figi -> IO
                .pure(GetCandlesResponse(Seq(candle(day, close = Quotation(150, 123456000)))))
            )
          )
          source <- buildSource(instruments, marketData)
          bars <- source.dailyBars(ticker, day, day)
        } yield {
          bars.map(_.close) must_== List(BigDecimal("150.123456"))
          bars.map(_.adjClose) must_== List(BigDecimal("150.123456")) // adjClose = close
        }
      }

      "chunks a multi-year range into at most one year per request" >> {
        val since = LocalDate.of(2020, 1, 1)
        val until = LocalDate.of(2023, 6, 15)
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          _ <- source.dailyBars(ticker, since, until)
          calls <- marketData.getCandlesCalls
        } yield calls must haveSize(4)
      }

      "skips candles that are not complete" >> {
        val complete = candle(LocalDate.of(2024, 1, 10), isComplete = true)
        val incomplete = candle(LocalDate.of(2024, 1, 11), isComplete = false)
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService(candles =
            Map(figi -> IO.pure(GetCandlesResponse(Seq(complete, incomplete))))
          )
          source <- buildSource(instruments, marketData)
          bars <- source.dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield bars.map(_.date) must_== List(LocalDate.of(2024, 1, 10))
      }

      "skips candles without a timestamp" >> {
        val withTime = candle(LocalDate.of(2024, 1, 10))
        val withoutTime = withTime.copy(time = None)
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService(candles =
            Map(figi -> IO.pure(GetCandlesResponse(Seq(withTime, withoutTime))))
          )
          source <- buildSource(instruments, marketData)
          bars <- source.dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
        } yield bars.map(_.date) must_== List(LocalDate.of(2024, 1, 10))
      }

      "reports an unknown ticker as not found" >> {
        for {
          instruments <- TcsMarketDataFakes.instrumentsService(
            Map(ticker.value -> IO.raiseError(Status.NOT_FOUND.asRuntimeException()))
          )
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          result <- source
            .dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
            .attempt
        } yield result must beLeft(MarketDataError.NotFound(ticker))
      }

      "reports rate limiting when fetching candles" >> {
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService(candles =
            Map(figi -> IO.raiseError(Status.RESOURCE_EXHAUSTED.asRuntimeException()))
          )
          source <- buildSource(instruments, marketData)
          result <- source
            .dailyBars(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
            .attempt
        } yield result must beLeft(MarketDataError.RateLimited(None))
      }

      "caches the resolved figi across two calls" >> {
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          _ <- source.instrumentInfo(ticker)
          _ <- source.instrumentInfo(ticker)
          calls <- instruments.getInstrumentByCalls
        } yield calls must haveSize(1)
      }
    }

    "latestQuote" >> {
      "uses the resolved figi's cached currency when fetching the latest price" >> {
        val now = ZonedDateTime.of(2024, 1, 10, 12, 0, 0, 0, zone)
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService(lastPrices =
            Map(
              figi -> IO.pure(
                GetLastPricesResponse(
                  Seq(
                    LastPrice(
                      figi = figi,
                      price = Some(Quotation(305, 0)),
                      time = Some(ts(now.toLocalDate))
                    )
                  )
                )
              )
            )
          )
          source <- buildSource(instruments, marketData)
          quote <- source.latestQuote(ticker)
        } yield {
          quote must beSome
          quote.map(_.price) must beSome(BigDecimal("305.000000"))
          quote.map(_.currency) must beSome("rub")
        }
      }
    }

    "instrumentInfo" >> {
      "maps the resolved instrument, setting the timezone to the configured zone id" >> {
        for {
          instruments <- workingInstruments
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          info <- source.instrumentInfo(ticker)
        } yield info must beSome.like { case i =>
          i.name must beSome("Sberbank")
          i.instrumentType must_== "share"
          i.currency must_== "rub"
          i.exchange must_== "MOEX"
          i.timezone must_== "Europe/Moscow"
          i.figi must beSome(figi)
        }
      }
    }

    "dividends" >> {

      def dividend(
        lastBuyDate: Option[LocalDate],
        units: Long = 1,
        nano: Int = 500000000,
        currency: String = "usd"
      ): Dividend =
        Dividend(
          dividendNet = Some(MoneyValue(currency = currency, units = units, nano = nano)),
          lastBuyDate = lastBuyDate.map(ts)
        )

      "derives the ex-date as the day after the last buy date, and the amount and currency from the net dividend" >> {
        for {
          instruments <- TcsMarketDataFakes.instrumentsService(
            Map(ticker.value -> IO.pure(InstrumentResponse(instrument = Some(instrument())))),
            dividends = Map(
              figi -> IO.pure(GetDividendsResponse(Seq(dividend(Some(LocalDate.of(2024, 5, 10))))))
            )
          )
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          events <- source.dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
        } yield {
          events must haveSize(1)
          events.head.exDate must_== LocalDate.of(2024, 5, 11)
          events.head.amount must_== BigDecimal("1.500000")
          events.head.currency must beSome("usd")
          events.head.source must_== Source.Tinkoff
        }
      }

      "skips entries without a last buy date" >> {
        for {
          instruments <- TcsMarketDataFakes.instrumentsService(
            Map(ticker.value -> IO.pure(InstrumentResponse(instrument = Some(instrument())))),
            dividends = Map(figi -> IO.pure(GetDividendsResponse(Seq(dividend(None)))))
          )
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          events <- source.dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
        } yield events must beEmpty
      }

      "skips entries with a zero net amount" >> {
        for {
          instruments <- TcsMarketDataFakes.instrumentsService(
            Map(ticker.value -> IO.pure(InstrumentResponse(instrument = Some(instrument())))),
            dividends = Map(
              figi -> IO.pure(
                GetDividendsResponse(
                  Seq(dividend(Some(LocalDate.of(2024, 5, 10)), units = 0, nano = 0))
                )
              )
            )
          )
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          events <- source.dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
        } yield events must beEmpty
      }

      "reports an unknown ticker as not found" >> {
        for {
          instruments <- TcsMarketDataFakes.instrumentsService(
            Map(ticker.value -> IO.raiseError(Status.NOT_FOUND.asRuntimeException()))
          )
          marketData <- TcsMarketDataFakes.marketDataService()
          source <- buildSource(instruments, marketData)
          result <- source
            .dividends(ticker, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
            .attempt
        } yield result must beLeft(MarketDataError.NotFound(ticker))
      }
    }
  }
}
