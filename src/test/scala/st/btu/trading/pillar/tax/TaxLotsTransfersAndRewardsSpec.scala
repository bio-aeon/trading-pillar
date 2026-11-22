package st.btu.trading.pillar.tax

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.testkit.AnalyticsFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.instrumentKey
import st.btu.trading.pillar.testkit.TaxFakes.lot

import java.time.{Instant, LocalDate, ZoneOffset}

class TaxLotsTransfersAndRewardsSpec extends Specification {

  private val accountId = AccountId(1)
  private val eur = Currency("EUR")
  private val usdt = Currency("USDT")

  private val coin = instrumentKey("COIN")

  private val d0 = LocalDate.of(2024, 1, 1)
  private val d1 = LocalDate.of(2024, 2, 1)
  private val d2 = LocalDate.of(2024, 3, 1)

  private def at(date: LocalDate): Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant

  private val noTickers: InstrumentKey => Option[Ticker] = _ => None

  private def operation(
    kind: OperationKind,
    date: LocalDate,
    quantity: BigDecimal,
    amount: BigDecimal = 0,
    price: Option[BigDecimal] = None,
    currency: String = "USDT",
    state: OperationState = OperationState.Executed
  ): AccountOperation =
    AnalyticsFakes.accountOperation(
      accountId,
      kind,
      amount,
      at(date),
      Some("COIN"),
      quantity,
      currency = currency,
      state = state,
      price = price
    )

  private def transferIn(
    date: LocalDate,
    quantity: BigDecimal,
    price: Option[BigDecimal] = None,
    state: OperationState = OperationState.Executed
  ): AccountOperation =
    operation(OperationKind.SecurityIn, date, quantity, price = price, state = state)

  private def buy(date: LocalDate, quantity: BigDecimal, cost: BigDecimal): AccountOperation =
    operation(OperationKind.Buy, date, quantity, amount = -cost)

  private def reward(
    date: LocalDate,
    quantity: BigDecimal,
    price: Option[BigDecimal] = None
  ): AccountOperation =
    operation(OperationKind.Reward, date, quantity, price = price)

  private def costOf(
    transfer: AccountOperation,
    cost: BigDecimal,
    currency: Currency,
    acquiredOn: Option[LocalDate] = None
  ): TransferCost =
    TransferCost(transfer.ref, cost, currency, acquiredOn)

  "TaxLots" >> {
    "fifo" >> {
      "a transfer in with an entered cost" >> {
        "opens its lot at the cost per unit, in the currency of the cost" >> {
          val transfer = transferIn(d0, 4)

          val ledger = TaxLots.fifo(
            List(transfer),
            noTickers,
            transferCosts = List(costOf(transfer, 6000, eur))
          )

          // 6000 euros for 4 units
          ledger.open(coin) must_== List(lot(coin, 4, 1500, d0, eur))
        }

        "is acquired on the day the cost gives, and goes before the lots acquired later" >> {
          val earlier = LocalDate.of(2022, 6, 1)
          val bought = buy(d1, 2, 100)
          val transfer = transferIn(d2, 4)

          val ledger = TaxLots.fifo(
            List(bought, transfer),
            noTickers,
            transferCosts = List(costOf(transfer, 400, eur, Some(earlier)))
          )

          ledger.open(coin) must_== List(
            lot(coin, 4, 100, earlier, eur),
            lot(coin, 2, 50, d1, usdt)
          )
        }

        "costs the units the cost names, whatever price the operation gives" >> {
          val transfer = transferIn(d0, 4, price = Some(9))

          val ledger = TaxLots.fifo(
            List(transfer),
            noTickers,
            transferCosts = List(costOf(transfer, 80, usdt))
          )

          ledger.open(coin) must_== List(lot(coin, 4, 20, d0, usdt))
        }

        "gives a lot that costs nothing, still acquired on the day given, when the cost is zero" >> {
          val earlier = LocalDate.of(2022, 6, 1)
          val transfer = transferIn(d0, 4)

          val ledger = TaxLots.fifo(
            List(transfer),
            noTickers,
            transferCosts = List(costOf(transfer, 0, eur, Some(earlier)))
          )

          ledger.open(coin) must_== List(Lot(coin, 4, Map.empty, earlier))
        }

        "a later sale reports the entered cost in its currency and the holding time from the day given" >> {
          val acquired = LocalDate.of(2023, 3, 1)
          val transfer = transferIn(d0, 4)
          val sale = operation(OperationKind.Sell, d1, -4, amount = 9000, currency = "USDT")

          val ledger = TaxLots.fifo(
            List(transfer, sale),
            noTickers,
            transferCosts = List(costOf(transfer, 6000, eur, Some(acquired)))
          )

          // From 2023-03-01 to 2024-02-01 is 337 days.
          ledger.realized must_== List(
            RealizedGain(coin, None, d1, 4, usdt, 9000, Map(eur -> BigDecimal(6000)), 337)
          )
        }

        "is ignored when the transfer in has another ref" >> {
          val transfer = transferIn(d0, 4, price = Some(9))
          val other = transferIn(d1, 1)

          val ledger =
            TaxLots.fifo(List(transfer), noTickers, transferCosts = List(costOf(other, 100, eur)))

          ledger.open(coin) must_== List(lot(coin, 4, 9, d0, usdt))
        }

        "is ignored when the transfer in was not executed" >> {
          val pending = transferIn(d0, 4, state = OperationState.Pending)

          val ledger =
            TaxLots.fifo(List(pending), noTickers, transferCosts = List(costOf(pending, 100, eur)))

          ledger.open must beEmpty
        }

        "is ignored when its ref names an operation that is not a transfer in" >> {
          val bought = buy(d0, 2, 100)

          val ledger =
            TaxLots.fifo(List(bought), noTickers, transferCosts = List(costOf(bought, 5, eur)))

          ledger.open(coin) must_== List(lot(coin, 2, 50, d0, usdt))
        }
      }

      "a transfer in with no entered cost" >> {
        "costs the operation's price in the operation's currency" >> {
          val ledger = TaxLots.fifo(List(transferIn(d0, 4, price = Some(9))), noTickers)

          ledger.open(coin) must_== List(lot(coin, 4, 9, d0, usdt))
        }
      }

      "a reward of units" >> {
        "opens a lot of the units received at their price per unit, in the currency of the price, on the day of the reward" >> {
          val ledger = TaxLots.fifo(List(reward(d0, 5, Some(BigDecimal("31.9")))), noTickers)

          ledger.open(coin) must_== List(lot(coin, 5, BigDecimal("31.9"), d0, usdt))
        }

        "opens a lot at no cost when no price is known" >> {
          val ledger = TaxLots.fifo(List(reward(d0, 5)), noTickers)

          ledger.open(coin) must_== List(Lot(coin, 5, Map.empty, d0))
        }

        "is sold from like any other lot, and the gain counts the units' value when received as their cost" >> {
          val ops = List(
            reward(d0, 5, Some(10)),
            operation(OperationKind.Sell, d1, -5, amount = 80, currency = "USDT")
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.realized must_== List(
            RealizedGain(coin, None, d1, 5, usdt, 80, Map(usdt -> BigDecimal(50)), 31)
          )
        }

        "of a negative quantity takes the units out of the lots like a transfer out" >> {
          val ops = List(buy(d0, 10, 1000), reward(d1, -4))

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open(coin) must_== List(lot(coin, 6, 100, d0, usdt))
          ledger.realized must beEmpty
        }

        "is not counted when it was not executed" >> {
          val pending =
            operation(OperationKind.Reward, d0, 5, price = Some(10), state = OperationState.Pending)

          TaxLots.fifo(List(pending), noTickers).open must beEmpty
        }
      }

      "a reward of cash" >> {
        "names no instrument, so it opens no lot" >> {
          val cash = AnalyticsFakes.accountOperation(
            accountId,
            OperationKind.Reward,
            25,
            at(d0),
            currency = "USDT"
          )

          TaxLots.fifo(List(cash), noTickers).open must beEmpty
        }
      }
    }
  }
}
