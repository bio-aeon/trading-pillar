package st.btu.trading.pillar.tax

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, Figi}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.testkit.AnalyticsFakes

import java.time.{LocalDate, ZoneOffset}

class TaxLotsSpec extends Specification {

  private val accountId = AccountId(1)

  private def op(
    opType: String,
    amount: BigDecimal,
    date: LocalDate,
    figi: String = "F1",
    quantity: Int = 0,
    price: BigDecimal = BigDecimal(0),
    status: String = "OPERATION_STATE_EXECUTED"
  ) =
    AnalyticsFakes.operation(
      accountId,
      opType,
      amount,
      date.atStartOfDay(ZoneOffset.UTC),
      Some(figi),
      quantity,
      currency = "USD",
      status = status,
      price = price
    )

  private val noTickers: Figi => Option[Ticker] = _ => None

  "TaxLots" >> {
    "fifo" >> {

      "fully matches a sell against a single buy lot" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = LocalDate.of(2024, 2, 1) // 31 days later
        val buy = op("OPERATION_TYPE_BUY", amount = -1000, date = d0, quantity = 10)
        val sell = op("OPERATION_TYPE_SELL", amount = 1600, date = d1, quantity = 10)

        val ledger = TaxLots.fifo(List(buy, sell), noTickers)

        ledger.open.get(Figi("F1")) must beNone
        ledger.unmatched.get(Figi("F1")) must beNone
        ledger.realized must_== List(RealizedGain(Figi("F1"), None, d1, 10, 1600, 1000, 600, 31))
      }

      "consumes a lot partially across two sells, keeping the remainder open" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(28)
        val d2 = d0.plusDays(40)
        val buy = op("OPERATION_TYPE_BUY", amount = -1000, date = d0, quantity = 10) // 100/unit
        val sell1 = op("OPERATION_TYPE_SELL", amount = 480, date = d1, quantity = 4)
        val sell2 = op("OPERATION_TYPE_SELL", amount = 750, date = d2, quantity = 6)

        val ledger = TaxLots.fifo(List(buy, sell1, sell2), noTickers)

        ledger.open.get(Figi("F1")) must beNone
        ledger.realized must_== List(
          RealizedGain(Figi("F1"), None, d1, 4, 480, 400, 80, 28),
          RealizedGain(Figi("F1"), None, d2, 6, 750, 600, 150, 40)
        )
      }

      "pro-rates a day's broker fee across that day's buys, by quantity share" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val buy1 = op("OPERATION_TYPE_BUY", amount = -1000, date = d0, quantity = 10) // 100/unit
        val buy2 = op("OPERATION_TYPE_BUY", amount = -4000, date = d0, quantity = 10) // 400/unit
        val fee = op("OPERATION_TYPE_BROKER_FEE", amount = -40, date = d0)

        val ledger = TaxLots.fifo(List(buy1, buy2, fee), noTickers)

        // buy1: (1000 + 40*10/20) / 10 = 102; buy2: (4000 + 40*10/20) / 10 = 402.
        ledger.open(Figi("F1")) must_== List(
          Lot(Figi("F1"), 10, 102, d0),
          Lot(Figi("F1"), 10, 402, d0)
        )
        ledger.realized must beEmpty
      }

      "pro-rates a day's broker fee across that day's sells, netting it from proceeds" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(10)
        val buy = op("OPERATION_TYPE_BUY", amount = -2000, date = d0, quantity = 20) // 100/unit
        val sell1 =
          op("OPERATION_TYPE_SELL", amount = 750, date = d1, quantity = 5) // gross 150/unit
        val sell2 =
          op("OPERATION_TYPE_SELL", amount = 2100, date = d1, quantity = 15) // gross 140/unit
        val fee = op("OPERATION_TYPE_BROKER_FEE", amount = -80, date = d1)

        val ledger = TaxLots.fifo(List(buy, sell1, sell2, fee), noTickers)

        // sell1 fee share = 80*5/20 = 20 -> proceeds 730, cost 500, gain 230.
        // sell2 fee share = 80*15/20 = 60 -> proceeds 2040, cost 1500, gain 540.
        ledger.realized must_== List(
          RealizedGain(Figi("F1"), None, d1, 5, 730, 500, 230, 10),
          RealizedGain(Figi("F1"), None, d1, 15, 2040, 1500, 540, 10)
        )
        ledger.open.get(Figi("F1")) must beNone
      }

      "opens a transferred-in lot at the operation's own price, ignoring same-day fees" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val transferIn =
          op("OPERATION_TYPE_INPUT_SECURITIES", amount = 0, date = d0, quantity = 7, price = 250)
        val fee = op("OPERATION_TYPE_BROKER_FEE", amount = -99, date = d0)

        val ledger = TaxLots.fifo(List(transferIn, fee), noTickers)

        ledger.open(Figi("F1")) must_== List(Lot(Figi("F1"), 7, 250, d0))
      }

      "consumes whatever lots exist and records the shortfall when selling more than held" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(15)
        val d2 = d0.plusDays(30)
        val buy = op("OPERATION_TYPE_BUY", amount = -500, date = d0, quantity = 5) // 100/unit
        val oversell = op("OPERATION_TYPE_SELL", amount = 960, date = d1, quantity = 8)
        val emptySell = op("OPERATION_TYPE_SELL", amount = 400, date = d2, quantity = 4)

        val ledger = TaxLots.fifo(List(buy, oversell, emptySell), noTickers)

        ledger.open.get(Figi("F1")) must beNone
        ledger.unmatched(Figi("F1")) must_== 7L
        ledger.realized must_== List(
          RealizedGain(Figi("F1"), None, d1, 5, 960, 500, 460, 15),
          RealizedGain(Figi("F1"), None, d2, 0, 400, 0, 400, 0)
        )
      }

      "ignores operations that are not in the executed state" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = d0.plusDays(10)
        val buy = op("OPERATION_TYPE_BUY", amount = -1000, date = d0, quantity = 10)
        val cancelledBuy =
          op(
            "OPERATION_TYPE_BUY",
            amount = -1,
            date = d0,
            quantity = 999,
            status = "OPERATION_STATE_CANCELED"
          )
        val sell = op("OPERATION_TYPE_SELL", amount = 1200, date = d1, quantity = 10)

        val ledger = TaxLots.fifo(List(buy, cancelledBuy, sell), noTickers)

        ledger.unmatched must beEmpty
        ledger.realized must_== List(RealizedGain(Figi("F1"), None, d1, 10, 1200, 1000, 200, 10))
      }

      "attaches the resolved ticker and sorts realized gains by sell date and figi" >> {
        val d0 = LocalDate.of(2024, 1, 1)
        val d1 = LocalDate.of(2024, 2, 1)
        val tickerOf: Figi => Option[Ticker] =
          Map(Figi("F1") -> Ticker("AAA")).get

        val f1Buy = op("OPERATION_TYPE_BUY", amount = -1000, date = d0, figi = "F1", quantity = 10)
        val f1Sell = op("OPERATION_TYPE_SELL", amount = 1200, date = d1, figi = "F1", quantity = 10)
        val f2Buy = op("OPERATION_TYPE_BUY", amount = -500, date = d0, figi = "F2", quantity = 5)
        val f2Sell = op("OPERATION_TYPE_SELL", amount = 700, date = d1, figi = "F2", quantity = 5)

        val ledger = TaxLots.fifo(List(f1Buy, f1Sell, f2Buy, f2Sell), tickerOf)

        ledger.realized.map(_.figi) must_== List(Figi("F1"), Figi("F2"))
        ledger.realized.map(_.ticker) must_== List(Some(Ticker("AAA")), None)
      }
    }
  }
}
