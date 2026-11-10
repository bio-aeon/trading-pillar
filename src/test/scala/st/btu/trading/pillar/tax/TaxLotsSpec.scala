package st.btu.trading.pillar.tax

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.testkit.AnalyticsFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.instrumentKey

import java.time.{Instant, LocalDate, ZoneOffset}

class TaxLotsSpec extends Specification {

  private val accountId = AccountId(1)
  private val usd = Currency("USD")

  private val f1 = instrumentKey("F1")
  private val f2 = instrumentKey("F2")

  private def at(date: LocalDate): Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant

  // An operation on the instrument F1 unless another is named, in dollars unless another currency
  // is named; `quantity` is signed.
  private def op(
    kind: OperationKind,
    amount: BigDecimal,
    date: LocalDate,
    instrument: String = "F1",
    quantity: BigDecimal = 0,
    price: Option[BigDecimal] = None,
    state: OperationState = OperationState.Executed,
    currency: String = "USD"
  ): AccountOperation =
    AnalyticsFakes.accountOperation(
      accountId,
      kind,
      amount,
      at(date),
      Some(instrument),
      quantity,
      currency = currency,
      state = state,
      price = price
    )

  private def buy(
    cost: BigDecimal,
    date: LocalDate,
    quantity: BigDecimal,
    instrument: String = "F1",
    currency: String = "USD"
  ) = op(OperationKind.Buy, -cost, date, instrument, quantity, currency = currency)

  private def sell(
    proceeds: BigDecimal,
    date: LocalDate,
    quantity: BigDecimal,
    instrument: String = "F1",
    currency: String = "USD"
  ) = op(OperationKind.Sell, proceeds, date, instrument, -quantity, currency = currency)

  // A fee of `charged`, which leaves the account as a negative amount.
  private def fee(
    charged: BigDecimal,
    date: LocalDate,
    instrument: String = "F1",
    currency: String = "USD"
  ) = op(OperationKind.Fee, -charged, date, instrument, currency = currency)

  private val noTickers: InstrumentKey => Option[Ticker] = _ => None

  "TaxLots" >> {
    "fifo" >> {

      "fully matches a sell against a single buy lot" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = LocalDate.of(2024, 2, 1) // 31 days later

        val ledger =
          TaxLots.fifo(List(buy(1000, d0, 10), sell(1600, d1, 10)), noTickers)

        ledger.open.get(f1) must beNone
        ledger.unmatched.get(f1) must beNone
        ledger.realized must_== List(RealizedGain(f1, None, d1, 10, usd, 1600, 1000, 600, 31))
      }

      "consumes a lot partially across two sells, keeping the remainder open" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(28)
        val d2 = d0.plusDays(40)
        // Bought at 100 a unit.
        val ops = List(buy(1000, d0, 10), sell(480, d1, 4), sell(750, d2, 6))

        val ledger = TaxLots.fifo(ops, noTickers)

        ledger.open.get(f1) must beNone
        ledger.realized must_== List(
          RealizedGain(f1, None, d1, 4, usd, 480, 400, 80, 28),
          RealizedGain(f1, None, d2, 6, usd, 750, 600, 150, 40)
        )
      }

      "keeps the unsold remainder of a lot open at its own unit cost and acquisition date" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(10)
        val ops = List(buy(1000, d0, 10), sell(480, d1, 4))

        val ledger = TaxLots.fifo(ops, noTickers)

        ledger.open(f1) must_== List(Lot(f1, 6, 100, d0))
      }

      "consumes the oldest lot first across several buys" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(10)
        val d2 = d0.plusDays(20)
        // 5 units at 100, then 5 at 200; 7 sold takes all of the first lot and 2 of the second.
        val ops = List(buy(500, d0, 5), buy(1000, d1, 5), sell(1400, d2, 7))

        val ledger = TaxLots.fifo(ops, noTickers)

        // Cost: 5 * 100 + 2 * 200 = 900, and the 5 units of 20 days and 2 of 10 give
        // (5 * 20 + 2 * 10) / 7 = 17.14 days.
        ledger.realized must_== List(RealizedGain(f1, None, d2, 7, usd, 1400, 900, 500, 17))
        ledger.open(f1) must_== List(Lot(f1, 3, 200, d1))
      }

      "pro-rates a day's fee across that day's buys, by quantity share" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val buy1 = buy(1000, d0, 10) // 100/unit
        val buy2 = buy(4000, d0, 10) // 400/unit

        val ledger = TaxLots.fifo(List(buy1, buy2, fee(40, d0)), noTickers)

        // buy1: (1000 + 40*10/20) / 10 = 102; buy2: (4000 + 40*10/20) / 10 = 402.
        ledger.open(f1) must_== List(Lot(f1, 10, 102, d0), Lot(f1, 10, 402, d0))
        ledger.realized must beEmpty
      }

      "pro-rates a day's fee across that day's sells, netting it from proceeds" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(10)
        val ops = List(
          buy(2000, d0, 20), // 100/unit
          sell(750, d1, 5), // gross 150/unit
          sell(2100, d1, 15), // gross 140/unit
          fee(80, d1)
        )

        val ledger = TaxLots.fifo(ops, noTickers)

        // sell1 fee share = 80*5/20 = 20 -> proceeds 730, cost 500, gain 230.
        // sell2 fee share = 80*15/20 = 60 -> proceeds 2040, cost 1500, gain 540.
        ledger.realized must_== List(
          RealizedGain(f1, None, d1, 5, usd, 730, 500, 230, 10),
          RealizedGain(f1, None, d1, 15, usd, 2040, 1500, 540, 10)
        )
        ledger.open.get(f1) must beNone
      }

      "fees in another currency than the trades" >> {

        "keeps a fee out of a buy's cost when it is in another currency, while a fee in the buy's currency is shared as before" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          // 10 units for 10000 Taiwan dollars, on a day that charged 50 Taiwan dollars and 2 dollars.
          val ops = List(
            buy(10000, d0, 10, currency = "TWD"),
            fee(50, d0, currency = "TWD"),
            fee(2, d0, currency = "USD")
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open(f1) must_== List(Lot(f1, 10, 1005, d0))
        }

        "keeps a fee out of a sell's proceeds when it is in another currency, while a fee in the sell's currency is netted" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          val d1 = d0.plusDays(10)
          val ops = List(
            buy(10000, d0, 10, currency = "TWD"),
            sell(12000, d1, 10, currency = "TWD"),
            fee(100, d1, currency = "TWD"),
            fee(3, d1, currency = "USD")
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.realized must_== List(
            RealizedGain(f1, None, d1, 10, Currency("TWD"), 11900, 10000, 1900, 10)
          )
        }

        "shares each currency's fees among that currency's trades only" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          // 10 units bought for 10000 Taiwan dollars and 5 for 100 dollars on one day, with a fee in
          // each currency.
          val ops = List(
            buy(10000, d0, 10, currency = "TWD"),
            buy(100, d0, 5, currency = "USD"),
            fee(50, d0, currency = "TWD"),
            fee(10, d0, currency = "USD")
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          // (10000 + 50) / 10 and (100 + 10) / 5.
          ledger.open(f1) must_== List(Lot(f1, 10, 1005, d0), Lot(f1, 5, 22, d0))
        }
      }

      "ignores the fees of another instrument and of another day" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val ops = List(buy(1000, d0, 10), fee(99, d0, instrument = "F2"), fee(77, d0.plusDays(1)))

        val ledger = TaxLots.fifo(ops, noTickers)

        ledger.open(f1) must_== List(Lot(f1, 10, 100, d0))
      }

      "opens a transferred-in lot at the operation's own price, ignoring same-day fees" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val transferIn =
          op(OperationKind.SecurityIn, 0, d0, quantity = 7, price = Some(250))

        val ledger = TaxLots.fifo(List(transferIn, fee(99, d0)), noTickers)

        ledger.open(f1) must_== List(Lot(f1, 7, 250, d0))
      }

      "opens a transferred-in lot at no cost when the broker gives no price" >> {
        val d0 = LocalDate.of(2024, 1, 1)

        val ledger =
          TaxLots.fifo(List(op(OperationKind.SecurityIn, 0, d0, quantity = 7)), noTickers)

        ledger.open(f1) must_== List(Lot(f1, 7, 0, d0))
      }

      "a transfer of securities out" >> {

        "takes the units out of the open lots at their cost, realizing no gain" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          val transferOut = op(OperationKind.SecurityOut, 0, d0.plusDays(5), quantity = -4)

          val ledger = TaxLots.fifo(List(buy(1000, d0, 10), transferOut), noTickers)

          ledger.open(f1) must_== List(Lot(f1, 6, 100, d0))
          ledger.realized must beEmpty
          ledger.unmatched must beEmpty
        }

        "takes the units from the oldest lots first" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          val d1 = d0.plusDays(10)
          val d2 = d0.plusDays(20)
          // 5 units at 100, then 5 at 200; 7 transferred takes all of the first lot and 2 of the
          // second.
          val ops = List(
            buy(500, d0, 5),
            buy(1000, d1, 5),
            op(OperationKind.SecurityOut, 0, d2, quantity = -7)
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open(f1) must_== List(Lot(f1, 3, 200, d1))
        }

        "leaves a later sale to be matched against the lots that remain" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          val d1 = d0.plusDays(5)
          val d2 = d0.plusDays(40)
          // 10 bought at 100 a unit, 4 transferred away, and the other 6 sold at 150 a unit.
          val ops = List(
            buy(1000, d0, 10),
            op(OperationKind.SecurityOut, 0, d1, quantity = -4),
            sell(900, d2, 6)
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open.get(f1) must beNone
          ledger.unmatched must beEmpty
          ledger.realized must_== List(RealizedGain(f1, None, d2, 6, usd, 900, 600, 300, 40))
        }

        "records the units beyond the open lots as unmatched" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          val ops =
            List(buy(500, d0, 5), op(OperationKind.SecurityOut, 0, d0.plusDays(5), quantity = -8))

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open.get(f1) must beNone
          ledger.unmatched(f1) must_== BigDecimal(3)
          ledger.realized must beEmpty
        }
      }

      "consumes whatever lots exist and records the shortfall when selling more than held" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(15)
        val d2 = d0.plusDays(30)
        val ops = List(
          buy(500, d0, 5), // 100/unit
          sell(960, d1, 8),
          sell(400, d2, 4)
        )

        val ledger = TaxLots.fifo(ops, noTickers)

        ledger.open.get(f1) must beNone
        ledger.unmatched(f1) must_== BigDecimal(7)
        ledger.realized must_== List(
          RealizedGain(f1, None, d1, 5, usd, 960, 500, 460, 15),
          RealizedGain(f1, None, d2, 0, usd, 400, 0, 400, 0)
        )
      }

      "ignores operations that are not in the executed state" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(10)
        val canceledBuy =
          op(OperationKind.Buy, -1, d0, quantity = 999, state = OperationState.Canceled)
        val pendingSell =
          op(OperationKind.Sell, 5, d1, quantity = -3, state = OperationState.Pending)

        val ledger = TaxLots.fifo(
          List(buy(1000, d0, 10), canceledBuy, pendingSell, sell(1200, d1, 10)),
          noTickers
        )

        ledger.unmatched must beEmpty
        ledger.realized must_== List(RealizedGain(f1, None, d1, 10, usd, 1200, 1000, 200, 10))
      }

      "attaches the resolved ticker and sorts realized gains by sell date and instrument" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = LocalDate.of(2024, 2, 1)
        val tickerOf: InstrumentKey => Option[Ticker] = Map(f1 -> Ticker("AAA")).get

        val ops = List(
          buy(1000, d0, 10, "F1"),
          sell(1200, d1, 10, "F1"),
          buy(500, d0, 5, "F2"),
          sell(700, d1, 5, "F2")
        )

        val ledger = TaxLots.fifo(ops, tickerOf)

        ledger.realized.map(_.instrument) must_== List(f1, f2)
        ledger.realized.map(_.ticker) must_== List(Some(Ticker("AAA")), None)
      }

      "keeps the open lots of each instrument apart" >> {
        val d0 = LocalDate.of(2024, 1, 1)

        val ledger = TaxLots.fifo(List(buy(1000, d0, 10, "F1"), buy(500, d0, 5, "F2")), noTickers)

        ledger.open must_== Map(f1 -> List(Lot(f1, 10, 100, d0)), f2 -> List(Lot(f2, 5, 100, d0)))
      }

      "fractional quantities" >> {

        "opens a lot of a fraction of a unit, costed per whole unit" >> {
          val d0 = LocalDate.of(2024, 1, 1)

          // Half a unit for 400, so 800 a unit.
          val ledger = TaxLots.fifo(List(buy(400, d0, BigDecimal("0.5"))), noTickers)

          ledger.open(f1) must_== List(Lot(f1, BigDecimal("0.5"), 800, d0))
        }

        "consumes part of a lot, matching and costing only the fraction sold" >> {
          val d0 = LocalDate.of(2024, 1, 1)
          val d1 = d0.plusDays(20)
          val ops = List(buy(400, d0, BigDecimal("0.5")), sell(200, d1, BigDecimal("0.2")))

          val ledger = TaxLots.fifo(ops, noTickers)

          // 0.2 of a unit costs 0.2 * 800 = 160, and sold for 200 it gains 40.
          ledger.realized must_==
            List(RealizedGain(f1, None, d1, BigDecimal("0.2"), usd, 200, 160, 40, 20))
          ledger.open(f1) must_== List(Lot(f1, BigDecimal("0.3"), 800, d0))
        }
      }

      "leaves the lots as they are on a currency exchange" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val exchangeLeg = op(OperationKind.Exchange, -1100, d0.plusDays(3))

        val ledger = TaxLots.fifo(List(buy(1000, d0, 10), exchangeLeg), noTickers)

        ledger.open(f1) must_== List(Lot(f1, 10, 100, d0))
        ledger.realized must beEmpty
      }

      "stamps each realized gain with the currency of its sale" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(10)
        val euros = AnalyticsFakes.accountOperation(
          accountId,
          OperationKind.Sell,
          1200,
          at(d1),
          Some("F1"),
          quantity = -10,
          currency = "EUR"
        )

        val ledger = TaxLots.fifo(List(buy(1000, d0, 10), euros), noTickers)

        ledger.realized.map(_.currency) must_== List(Currency("EUR"))
      }

      "opens no lot for a buy that executed no units" >> {
        val d0 = LocalDate.of(2024, 1, 1)

        val ledger = TaxLots.fifo(List(op(OperationKind.Buy, 0, d0, quantity = 0)), noTickers)

        ledger.open must beEmpty
      }
    }
  }
}
