package st.btu.trading.pillar.analytics.portfolio

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.{Bar, InstrumentInfo, Source, Ticker}
import st.btu.trading.pillar.testkit.{AnalyticsFakes, MarketDataFakes}
import st.btu.trading.pillar.testkit.AnalyticsFakes.operation

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

class PortfolioValuationServiceImplSpec extends Specification with CatsEffect {

  private val accountId = AccountId(1)

  private def dt(date: LocalDate): ZonedDateTime = date.atStartOfDay(ZoneOffset.UTC)

  private def bar(
    ticker: Ticker,
    date: LocalDate,
    close: BigDecimal,
    source: Source = Source.Yahoo
  ): Bar = Bar(ticker, date, close, close, close, close, close, 0L, source)

  private def info(ticker: Ticker, figi: String, currency: String = "RUB"): InstrumentInfo =
    InstrumentInfo(
      ticker,
      None,
      "share",
      currency,
      "MOEX",
      "Europe/Moscow",
      Some(figi),
      ZonedDateTime.now
    )

  private def buildService(
    ops: List[st.btu.trading.pillar.models.domain.TcsPortfolioOperation],
    instruments: Map[Ticker, InstrumentInfo] = Map.empty,
    bars: List[Bar] = Nil,
    config: ValuationConfig = ValuationConfig()
  ): IO[PortfolioValuationService[IO]] =
    for {
      opStorage <- AnalyticsFakes.tcsPortfolioOperationStorage(ops)
      instrumentStorage <- MarketDataFakes.instrumentStorage(instruments)
      priceHistory <- MarketDataFakes.priceHistoryStorage(bars)
    } yield PortfolioValuationServiceImpl
      .create[IO](opStorage, instrumentStorage, priceHistory, config)

  "PortfolioValuationServiceImpl" >> {
    "dailySeries" >> {

      "tracks cash, positions, invested and cashFlow across a deposit, a buy, a withdrawal and a sell" >> {
        val d0 = LocalDate.of(2026, 1, 1)
        val d1 = d0.plusDays(1)
        val d2 = d0.plusDays(2)
        val d3 = d0.plusDays(3)
        val aaa = Ticker("AAA")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -500,
            dt(d1),
            figi = Some("F1"),
            quantity = 10
          ),
          operation(accountId, "OPERATION_TYPE_OUTPUT", -200, dt(d2)),
          operation(accountId, "OPERATION_TYPE_SELL", 240, dt(d3), figi = Some("F1"), quantity = 4)
        )
        val bars = List(bar(aaa, d1, 60), bar(aaa, d2, 60), bar(aaa, d3, 60))

        for {
          service <- buildService(ops, Map(aaa -> info(aaa, "F1")), bars)
          series <- service.dailySeries(accountId, d0, d3)
        } yield {
          series.points.map(p => (p.date, p.value, p.invested, p.cashFlow)) must_== Vector(
            (d0, BigDecimal(1000), BigDecimal(1000), BigDecimal(1000)),
            (d1, BigDecimal(1100), BigDecimal(1000), BigDecimal(0)),
            (d2, BigDecimal(900), BigDecimal(800), BigDecimal(-200)),
            (d3, BigDecimal(900), BigDecimal(800), BigDecimal(0))
          )
          series.unpricedInstruments must beEmpty
          series.unmappedFigis must beEmpty
        }
      }

      "carries the last known close forward across a day with no bar" >> {
        val d0 = LocalDate.of(2026, 2, 1)
        val d1 = d0.plusDays(1)
        val d2 = d0.plusDays(2)
        val bbb = Ticker("BBB")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(accountId, "OPERATION_TYPE_BUY", -100, dt(d0), figi = Some("F2"), quantity = 5)
        )
        // No bar on d1: its value must reuse d0's close rather than treating BBB as unpriced.
        val bars = List(bar(bbb, d0, 20), bar(bbb, d2, 25))

        for {
          service <- buildService(ops, Map(bbb -> info(bbb, "F2")), bars)
          series <- service.dailySeries(accountId, d0, d2)
        } yield {
          series.points.map(p => (p.date, p.value)) must_== Vector(
            (d0, BigDecimal(1000)),
            (d1, BigDecimal(1000)),
            (d2, BigDecimal(1025))
          )
          series.unpricedInstruments must beEmpty
        }
      }

      "converts a USD position to the base currency using the latest USDRUB=X close" >> {
        val d0 = LocalDate.of(2026, 3, 1)
        val ccc = Ticker("CCC")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(accountId, "OPERATION_TYPE_BUY", -100, dt(d0), figi = Some("F3"), quantity = 2)
        )
        val bars = List(bar(ccc, d0, 50), bar(Ticker("USDRUB=X"), d0, 90))

        for {
          service <- buildService(ops, Map(ccc -> info(ccc, "F3", currency = "USD")), bars)
          series <- service.dailySeries(accountId, d0, d0)
        } yield {
          // cash 900 (1000 - 100) + 2 * 50 USD * 90 RUB/USD = 900 + 9000 = 9900
          series.points.map(_.value) must_== Vector(BigDecimal(9900))
          series.unpricedInstruments must beEmpty
        }
      }

      "counts an unmapped FIGI and an unpriced mapped instrument, excluding both from value" >> {
        val d0 = LocalDate.of(2026, 4, 1)
        val ddd = Ticker("DDD")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -50,
            dt(d0),
            figi = Some("UNMAPPED"),
            quantity = 3
          ),
          operation(accountId, "OPERATION_TYPE_BUY", -70, dt(d0), figi = Some("F4"), quantity = 7)
        )
        // DDD (F4) is mapped but has no stored bars in either source.

        for {
          service <- buildService(ops, Map(ddd -> info(ddd, "F4")), bars = Nil)
          series <- service.dailySeries(accountId, d0, d0)
        } yield {
          series.points.map(_.value) must_== Vector(BigDecimal(880))
          series.unmappedFigis must_== Set("UNMAPPED")
          series.unpricedInstruments must_== Set("DDD")
        }
      }
    }

    "positionsAsOf" >> {

      "prices every position, with cash/position weights summing to 1 of the total" >> {
        val d0 = LocalDate.of(2026, 5, 1)
        val aaa = Ticker("AAA")
        val bbb = Ticker("BBB")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -500,
            dt(d0),
            figi = Some("F1"),
            quantity = 10
          ),
          operation(accountId, "OPERATION_TYPE_BUY", -300, dt(d0), figi = Some("F2"), quantity = 3)
        )
        val bars = List(bar(aaa, d0, 50), bar(bbb, d0, 100))
        val instruments = Map(aaa -> info(aaa, "F1"), bbb -> info(bbb, "F2"))

        for {
          service <- buildService(ops, instruments, bars)
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.date must_== d0
          snapshot.currency must_== "RUB"
          snapshot.cash must_== BigDecimal(200)
          snapshot.total must_== BigDecimal(1000)
          snapshot.unpriced must_== 0

          val byTicker = snapshot.positions.map(p => p.ticker.map(_.value).get -> p).toMap
          byTicker("AAA").quantity must_== 10
          byTicker("AAA").price must beSome(BigDecimal(50))
          byTicker("AAA").value must beSome(BigDecimal(500))
          byTicker("AAA").weight must beSome(BigDecimal(0.5))
          byTicker("AAA").instrumentType must beSome("share")
          byTicker("AAA").currency must beSome("RUB")
          byTicker("AAA").exchange must beSome("MOEX")
          byTicker("BBB").value must beSome(BigDecimal(300))
          byTicker("BBB").weight must beSome(BigDecimal(0.3))

          val weightSum = snapshot.positions.flatMap(_.weight).sum + snapshot.cash / snapshot.total
          weightSum must_== BigDecimal(1)
        }
      }

      "treats OPERATION_TYPE_INPUT_SECURITIES as a buy-like transfer-in, increasing the position's quantity" >> {
        val d0 = LocalDate.of(2026, 7, 1)
        val aaa = Ticker("AAA")

        val ops = List(
          operation(
            accountId,
            "OPERATION_TYPE_INPUT_SECURITIES",
            0,
            dt(d0),
            figi = Some("F1"),
            quantity = 5
          )
        )
        val bars = List(bar(aaa, d0, 20))

        for {
          service <- buildService(ops, Map(aaa -> info(aaa, "F1")), bars)
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.positions must haveSize(1)
          val position = snapshot.positions.head
          position.quantity must_== 5
          position.value must beSome(BigDecimal(100)) // 5 * 20
          snapshot.cash must_== BigDecimal(0)
          snapshot.total must_== BigDecimal(100)
        }
      }

      "converts a USD position's value to the base currency while leaving price in its own currency" >> {
        val d0 = LocalDate.of(2026, 6, 1)
        val ccc = Ticker("CCC")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(accountId, "OPERATION_TYPE_BUY", -100, dt(d0), figi = Some("F3"), quantity = 2)
        )
        val bars = List(bar(ccc, d0, 50), bar(Ticker("USDRUB=X"), d0, 90))

        for {
          service <- buildService(ops, Map(ccc -> info(ccc, "F3", currency = "USD")), bars)
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          val position = snapshot.positions.head
          position.currency must beSome("USD")
          position.price must beSome(BigDecimal(50)) // native currency, unconverted
          position.value must beSome(BigDecimal(9000)) // 2 * 50 USD * 90 RUB/USD
          snapshot.cash must_== BigDecimal(900)
          snapshot.total must_== BigDecimal(9900)
        }
      }

      "lists an unmapped and a mapped-but-unpriced position with no value, weight or price" >> {
        val d0 = LocalDate.of(2026, 7, 1)
        val ddd = Ticker("DDD")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -50,
            dt(d0),
            figi = Some("UNMAPPED"),
            quantity = 3
          ),
          operation(accountId, "OPERATION_TYPE_BUY", -70, dt(d0), figi = Some("F4"), quantity = 7)
        )

        for {
          service <- buildService(ops, Map(ddd -> info(ddd, "F4")), bars = Nil)
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.total must_== BigDecimal(880)
          snapshot.unpriced must_== 2

          val byFigi = snapshot.positions.map(p => p.figi.value -> p).toMap
          byFigi.keySet must_== Set("UNMAPPED", "F4")

          val unmapped = byFigi("UNMAPPED")
          unmapped.ticker must beNone
          unmapped.instrumentType must beNone
          unmapped.currency must beNone
          unmapped.exchange must beNone
          unmapped.quantity must_== 3
          unmapped.price must beNone
          unmapped.value must beNone
          unmapped.weight must beNone

          val mapped = byFigi("F4")
          mapped.ticker must beSome(ddd)
          mapped.quantity must_== 7
          mapped.price must beNone
          mapped.value must beNone
          mapped.weight must beNone
        }
      }

      "excludes a fully closed position (net quantity 0) from the snapshot" >> {
        val d0 = LocalDate.of(2026, 8, 1)
        val aaa = Ticker("AAA")

        val ops = List(
          operation(accountId, "OPERATION_TYPE_INPUT", 1000, dt(d0)),
          operation(
            accountId,
            "OPERATION_TYPE_BUY",
            -500,
            dt(d0),
            figi = Some("F1"),
            quantity = 10
          ),
          operation(accountId, "OPERATION_TYPE_SELL", 550, dt(d0), figi = Some("F1"), quantity = 10)
        )
        val bars = List(bar(aaa, d0, 55))

        for {
          service <- buildService(ops, Map(aaa -> info(aaa, "F1")), bars)
          snapshot <- service.positionsAsOf(accountId, d0)
        } yield {
          snapshot.positions must beEmpty
          snapshot.cash must_== BigDecimal(1050)
          snapshot.total must_== BigDecimal(1050)
          snapshot.unpriced must_== 0
        }
      }
    }
  }
}
