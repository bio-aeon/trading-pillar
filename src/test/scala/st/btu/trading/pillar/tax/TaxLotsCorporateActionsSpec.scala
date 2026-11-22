package st.btu.trading.pillar.tax

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.testkit.AnalyticsFakes
import st.btu.trading.pillar.testkit.MarketDataFakes.instrumentKey
import st.btu.trading.pillar.testkit.TaxFakes.{lot, realizedGain}

import java.time.{Instant, LocalDate, ZoneOffset}

class TaxLotsCorporateActionsSpec extends Specification {

  private val accountId = AccountId(1)

  private val usd = Currency("USD")
  private val eur = Currency("EUR")

  private val f1 = instrumentKey("F1")
  private val f2 = instrumentKey("F2")
  private val f3 = instrumentKey("F3")

  private val d0 = LocalDate.of(2024, 1, 1)
  private val d1 = LocalDate.of(2024, 2, 1)
  private val d2 = LocalDate.of(2024, 2, 15)

  // The day of every action below: 60 days after d0, 2024 being a leap year.
  private val actionDay = LocalDate.of(2024, 3, 1)
  private val afterAction = LocalDate.of(2024, 4, 1)

  private def at(date: LocalDate): Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant

  private val noTickers: InstrumentKey => Option[Ticker] = _ => None

  private def buy(
    instrument: String,
    cost: BigDecimal,
    quantity: BigDecimal,
    date: LocalDate,
    currency: String = "USD"
  ): AccountOperation =
    AnalyticsFakes.accountOperation(
      accountId,
      OperationKind.Buy,
      -cost,
      at(date),
      Some(instrument),
      quantity,
      currency = currency
    )

  private def sell(
    instrument: String,
    proceeds: BigDecimal,
    quantity: BigDecimal,
    date: LocalDate
  ): AccountOperation =
    AnalyticsFakes.accountOperation(
      accountId,
      OperationKind.Sell,
      proceeds,
      at(date),
      Some(instrument),
      -quantity,
      currency = "USD"
    )

  // Three lots of 100 units of `instrument`, costing 300 each. A quantity shared among them, a third
  // to each, does not end in decimals.
  private def threeLots(instrument: String): List[AccountOperation] =
    List(
      buy(instrument, 300, 100, d0),
      buy(instrument, 300, 100, d1),
      buy(instrument, 300, 100, d2)
    )

  // A leg of an action on `instrument`, `quantity` signed, carrying `cash` of the action's cash. The
  // legs of one action share `group`.
  private def leg(
    kind: OperationKind,
    instrument: String,
    quantity: BigDecimal,
    date: LocalDate = actionDay,
    price: Option[BigDecimal] = None,
    cash: BigDecimal = 0,
    group: Option[String] = Some("A1"),
    currency: String = "USD"
  ): AccountOperation =
    AnalyticsFakes
      .accountOperation(
        accountId,
        kind,
        cash,
        at(date),
        Some(instrument),
        quantity,
        currency = currency,
        price = price
      )
      .copy(group = group.map(OperationGroup(_)))

  private def conversion(
    instrument: String,
    quantity: BigDecimal,
    price: Option[BigDecimal] = None,
    cash: BigDecimal = 0,
    date: LocalDate = actionDay,
    currency: String = "USD"
  ): AccountOperation =
    leg(OperationKind.Conversion, instrument, quantity, date, price, cash, currency = currency)

  private def allocation(group: String, instrument: InstrumentKey, fraction: BigDecimal) =
    CorporateActionAllocation(OperationGroup(group), instrument, fraction)

  "TaxLots" >> {
    "fifo" >> {

      "a split" >> {
        "a forward split multiplies the units of every lot and divides its unit cost, keeping each lot's cost and date" >> {
          val ops = List(
            buy("F1", 1000, 10, d0), // 100 a unit
            buy("F1", 4000, 20, d1), // 200 a unit
            leg(OperationKind.Split, "F1", 30) // 30 held, so every lot doubles
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open(f1) must_== List(lot(f1, 20, 50, d0), lot(f1, 40, 100, d1))
          ledger.realized must beEmpty
          ledger.unmatched must beEmpty
        }

        "a reverse split divides the units of every lot and multiplies its unit cost" >> {
          val ops = List(
            buy("F1", 1000, 100, d0), // 10 a unit
            leg(OperationKind.Split, "F1", -75) // 25 of the 100 units stay
          )

          TaxLots.fifo(ops, noTickers).open(f1) must_== List(lot(f1, 25, 40, d0))
        }

        "the lots it leaves add up to the units held, though the division does not end" >> {
          val ops =
            threeLots("F1") ++ List(
              leg(OperationKind.Split, "F1", -200),
              sell("F1", 1000, 100, afterAction)
            )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open must beEmpty
          ledger.unmatched must beEmpty
        }

        "scales the unit cost in every currency a lot was paid in" >> {
          val ops = List(
            // 10 units for 1000 euros, with a fee of 20 dollars, so 100 euros and 2 dollars a unit
            buy("F1", 1000, 10, d0, currency = "EUR"),
            AnalyticsFakes.accountOperation(
              accountId,
              OperationKind.Fee,
              -20,
              at(d0),
              Some("F1"),
              currency = "USD"
            ),
            leg(OperationKind.Split, "F1", 10) // 10 held, so the lot doubles
          )

          TaxLots.fifo(ops, noTickers).open(f1) must_==
            List(Lot(f1, 20, Map(eur -> BigDecimal(50), usd -> BigDecimal(1)), d0))
        }

        "units that would leave nothing close the lots without a gain, the rest being unmatched" >> {
          val ops = List(buy("F1", 1000, 10, d0), leg(OperationKind.Split, "F1", -12))

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open must beEmpty
          ledger.realized must beEmpty
          ledger.unmatched(f1) must_== BigDecimal(2)
        }

        "on an instrument with no lots" >> {
          "growing units open a lot at no cost on the day of the split" >> {
            val ledger = TaxLots.fifo(List(leg(OperationKind.Split, "F1", 5)), noTickers)

            ledger.open(f1) must_== List(lot(f1, 5, 0, actionDay))
          }

          "shrinking units are unmatched" >> {
            val ledger = TaxLots.fifo(List(leg(OperationKind.Split, "F1", -5)), noTickers)

            ledger.open must beEmpty
            ledger.unmatched(f1) must_== BigDecimal(5)
          }
        }
      }

      "a stock dividend" >> {
        "opens a lot of the units received at the market value per unit, on the day of the dividend" >> {
          val dividend = leg(OperationKind.StockDividend, "F1", 5, price = Some(BigDecimal("31.9")))

          TaxLots.fifo(List(dividend), noTickers).open(f1) must_==
            List(lot(f1, 5, BigDecimal("31.9"), actionDay))
        }

        "opens a lot at no cost when the broker reports no value" >> {
          val dividend = leg(OperationKind.StockDividend, "F1", 5)

          TaxLots.fifo(List(dividend), noTickers).open(f1) must_== List(lot(f1, 5, 0, actionDay))
        }

        "of a negative quantity takes the units out of the lots like a transfer out" >> {
          val ops = List(buy("F1", 1000, 10, d0), leg(OperationKind.StockDividend, "F1", -4))

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open(f1) must_== List(lot(f1, 6, 100, d0))
          ledger.realized must beEmpty
        }
      }

      "a conversion" >> {
        "a reverse split onto a new instrument moves each lot with its units, cost and date" >> {
          // 1 for 25: 500 units of F1 become 20 of F2.
          val ops = List(
            buy("F1", 500, 250, d0), // 2 a unit
            buy("F1", 1500, 250, d1), // 6 a unit
            conversion("F1", -500),
            conversion("F2", 20)
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open must_== Map(f2 -> List(lot(f2, 10, 50, d0), lot(f2, 10, 150, d1)))
          ledger.realized must beEmpty
        }

        "a stock-for-stock merger gives the new shares the whole cost of the old ones" >> {
          val ops = List(
            buy("F1", 1000, 100, d0),
            conversion("F1", -100, price = Some(20)),
            conversion("F2", 50, price = Some(40))
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open must_== Map(f2 -> List(lot(f2, 50, 20, d0)))
          ledger.realized must beEmpty
        }

        "a merger paid in cash and stock sells the share of the old shares the cash paid for" >> {
          // 100 shares costing 1000 become 50 shares worth 2000, with 500 in cash: the cash is
          // 500 / (500 + 2000) of the whole, so it pays for 20 of the 100 shares, which cost 200.
          val ops = List(
            buy("F1", 1000, 100, d0),
            conversion("F1", -100, cash = 500),
            conversion("F2", 50, price = Some(40))
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.realized must_== List(realizedGain(f1, actionDay, 20, 500, 200, 60))
          ledger.open must_== Map(f2 -> List(lot(f2, 50, 16, d0)))
        }

        "carries the currency of the cost to the units received" >> {
          val ops = List(
            buy("F1", 1000, 100, d0, currency = "EUR"),
            conversion("F1", -100, price = Some(20)),
            conversion("F2", 50, price = Some(40))
          )

          TaxLots.fifo(ops, noTickers).open must_== Map(f2 -> List(lot(f2, 50, 20, d0, eur)))
        }

        "a merger paid in cash and stock realizes a gain in the currency of the cash, whose cost basis keeps the currencies of the lots" >> {
          // 100 shares costing 1000 euros become 50 shares worth 2000 dollars, with 500 dollars in
          // cash: the cash is 500 / (500 + 2000) of the whole, so it pays for 20 of the 100 shares,
          // which cost 200 euros.
          val ops = List(
            buy("F1", 1000, 100, d0, currency = "EUR"),
            conversion("F1", -100, cash = 500),
            conversion("F2", 50, price = Some(40))
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.realized must_== List(
            RealizedGain(f1, None, actionDay, 20, usd, 500, Map(eur -> BigDecimal(200)), 60)
          )
          ledger.open must_== Map(f2 -> List(lot(f2, 50, 16, d0, eur)))
        }

        "a rights subscription adds the cash paid to the cost of the shares received in the currency of the cash" >> {
          // 100 shares bought for 1000 euros are converted into 50 shares for 500 dollars more, so
          // each share received costs 20 euros and 10 dollars.
          val ops = List(
            buy("F1", 1000, 100, d0, currency = "EUR"),
            conversion("F1", -100, cash = -500),
            conversion("F2", 50)
          )

          TaxLots.fifo(ops, noTickers).open must_==
            Map(f2 -> List(Lot(f2, 50, Map(eur -> BigDecimal(20), usd -> BigDecimal(10)), d0)))
        }

        "a rights subscription adds the cash paid to the cost of the shares received" >> {
          val ops = List(
            buy("F1", 0, 1200, d0), // 1200 rights, which cost nothing
            conversion("F1", -1200, cash = -1788),
            conversion("F2", 120)
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open must_== Map(f2 -> List(lot(f2, 120, BigDecimal("14.9"), d0)))
          ledger.realized must beEmpty
        }

        "several instruments received share the cost by their value" >> {
          val ops = List(
            buy("F1", 1000, 100, d0),
            conversion("F1", -100),
            conversion("F2", 40, price = Some(10)), // 400
            conversion("F3", 30, price = Some(20)) // 600
          )

          TaxLots.fifo(ops, noTickers).open must_==
            Map(f2 -> List(lot(f2, 40, 10, d0)), f3 -> List(lot(f3, 30, 20, d0)))
        }

        "several instruments received share the cost by their units when one has no price" >> {
          val ops = List(
            buy("F1", 1000, 100, d0),
            conversion("F1", -100),
            conversion("F2", 40, price = Some(10)),
            conversion("F3", 60)
          )

          TaxLots.fifo(ops, noTickers).open must_==
            Map(f2 -> List(lot(f2, 40, 10, d0)), f3 -> List(lot(f3, 60, 10, d0)))
        }

        "several instruments given up each give the units received a share by their value" >> {
          val ops = List(
            buy("F1", 600, 100, d0), // 6 a unit, 600 in all
            buy("F3", 400, 50, d1), // 8 a unit, 400 in all
            conversion("F1", -100, price = Some(6)),
            conversion("F3", -50, price = Some(8)),
            conversion("F2", 10)
          )

          // F1 is 600 of the 1000 given up and F3 is 400, so F2's 10 units are 6 and 4.
          TaxLots.fifo(ops, noTickers).open must_==
            Map(f2 -> List(lot(f2, 6, 100, d0), lot(f2, 4, 100, d1)))
        }

        "an instrument given up that is valued at nothing still carries its cost, the units received being shared by units" >> {
          val ops = List(
            buy("F1", 600, 100, d0),
            buy("F3", 400, 100, d1),
            conversion("F1", -100, price = Some(0)),
            conversion("F3", -100, price = Some(4)),
            conversion("F2", 10)
          )

          // F1 and F3 gave up 100 units each, so F2's 10 units are 5 and 5, at 600 and at 400.
          TaxLots.fifo(ops, noTickers).open must_==
            Map(f2 -> List(lot(f2, 5, 120, d0), lot(f2, 5, 80, d1)))
        }

        "the lots of what was received add up to its units, though the division does not end" >> {
          val ops = threeLots("F1") ++ List(
            conversion("F1", -300),
            conversion("F2", 100),
            sell("F2", 1000, 100, afterAction)
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open must beEmpty
          ledger.unmatched must beEmpty
        }

        "units given up beyond the lots are unmatched, and the units received for them cost nothing" >> {
          val ops = List(
            buy("F1", 600, 60, d0),
            conversion("F1", -100, date = d1),
            conversion("F2", 50, date = d1)
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.unmatched(f1) must_== BigDecimal(40)
          ledger.open must_== Map(f2 -> List(lot(f2, 30, 20, d0), lot(f2, 20, 0, d1)))
        }

        "units received with nothing given up open a lot on the day, costing the cash paid" >> {
          val ledger =
            TaxLots.fifo(List(conversion("F2", 10, cash = -100)), noTickers)

          ledger.open must_== Map(f2 -> List(lot(f2, 10, 10, actionDay)))
        }

        "the legs of a group take effect together, whichever of them comes first" >> {
          val received = conversion("F2", 50)
          val gaveUp = conversion("F1", -100).copy(occurredAt = at(actionDay).plusSeconds(1))
          val ops = List(buy("F1", 1000, 100, d0), received, gaveUp)

          TaxLots.fifo(ops, noTickers).open must_== Map(f2 -> List(lot(f2, 50, 20, d0)))
        }

        "a leg without a group is a group of its own" >> {
          val ops = List(
            buy("F1", 1000, 100, d0),
            leg(OperationKind.Conversion, "F1", -100, group = None),
            leg(OperationKind.Conversion, "F2", 50, group = None)
          )

          // Nothing links the legs, so what was received costs nothing.
          TaxLots.fifo(ops, noTickers).open must_== Map(f2 -> List(lot(f2, 50, 0, actionDay)))
        }

        "lots carried into an instrument already held go before the lots bought later" >> {
          val ops = List(
            buy("F1", 50, 10, d0),
            buy("F2", 100, 10, d1),
            conversion("F1", -10),
            conversion("F2", 10)
          )

          TaxLots.fifo(ops, noTickers).open(f2) must_==
            List(lot(f2, 10, 5, d0), lot(f2, 10, 10, d1))
        }
      }

      "a spin-off" >> {
        // The parent F1 has two lots, 60 units costing 600 and 40 units costing 800, and the spin-off
        // gives 10 units of F2. Its leg of no units names the parent.
        val parentLots = List(buy("F1", 600, 60, d0), buy("F1", 800, 40, d1))
        val spinoff =
          List(leg(OperationKind.Spinoff, "F2", 10), leg(OperationKind.Spinoff, "F1", 0))

        "without an allocation gives the new instrument a lot at no cost for each parent lot, dated like it" >> {
          val ledger = TaxLots.fifo(parentLots ++ spinoff, noTickers)

          ledger.open must_== Map(
            f1 -> List(lot(f1, 60, 10, d0), lot(f1, 40, 20, d1)),
            f2 -> List(lot(f2, 6, 0, d0), lot(f2, 4, 0, d1))
          )
        }

        "with an allocation moves that fraction of each parent lot's cost to the new instrument" >> {
          val ledger =
            TaxLots.fifo(
              parentLots ++ spinoff,
              noTickers,
              List(allocation("A1", f2, BigDecimal("0.2")))
            )

          // 0.2 of the lots' 600 and 800 is 120 and 160, over 6 and 4 units; the parent keeps 0.8.
          ledger.open must_== Map(
            f1 -> List(lot(f1, 60, 8, d0), lot(f1, 40, 16, d1)),
            f2 -> List(lot(f2, 6, 20, d0), lot(f2, 4, 40, d1))
          )
        }

        "moves the allocated fraction of the cost in each currency to the new instrument" >> {
          val ops = List(
            // 100 units costing 1000 euros, with a fee of 50 dollars: 10 euros and 0.5 dollar a unit
            buy("F1", 1000, 100, d0, currency = "EUR"),
            AnalyticsFakes.accountOperation(
              accountId,
              OperationKind.Fee,
              -50,
              at(d0),
              Some("F1"),
              currency = "USD"
            ),
            leg(OperationKind.Spinoff, "F2", 10),
            leg(OperationKind.Spinoff, "F1", 0)
          )

          val ledger =
            TaxLots.fifo(ops, noTickers, List(allocation("A1", f2, BigDecimal("0.2"))))

          // 0.2 of the lot's 1000 euros and 50 dollars goes to the 10 units of F2.
          ledger.open must_== Map(
            f1 -> List(Lot(f1, 100, Map(eur -> BigDecimal(8), usd -> BigDecimal("0.4")), d0)),
            f2 -> List(Lot(f2, 10, Map(eur -> BigDecimal(20), usd -> BigDecimal(1)), d0))
          )
        }

        "ignores an allocation of another spin-off" >> {
          val ledger =
            TaxLots.fifo(
              parentLots ++ spinoff,
              noTickers,
              List(allocation("A2", f2, BigDecimal("0.2")))
            )

          ledger.open(f2) must_== List(lot(f2, 6, 0, d0), lot(f2, 4, 0, d1))
        }

        "with two new instruments" >> {
          // F2 and F3 are spun off together, 10 units of F2 and 5 of F3, with F1 as the parent.
          val twoChildren = List(
            leg(OperationKind.Spinoff, "F2", 10),
            leg(OperationKind.Spinoff, "F3", 5),
            leg(OperationKind.Spinoff, "F1", 0)
          )

          "counts an allocation of more than the whole cost as the whole" >> {
            val allocations = List(allocation("A1", f2, 2), allocation("A1", f3, 1))

            val ledger = TaxLots.fifo(parentLots ++ twoChildren, noTickers, allocations)

            // Each counts as the whole, so the two share the cost by halves and the parent keeps none.
            ledger.open must_== Map(
              f1 -> List(lot(f1, 60, 0, d0), lot(f1, 40, 0, d1)),
              f2 -> List(lot(f2, 6, 50, d0), lot(f2, 4, 100, d1)),
              f3 -> List(lot(f3, 3, 100, d0), lot(f3, 2, 200, d1))
            )
          }

          "counts a negative allocation as none" >> {
            val allocations =
              List(
                allocation("A1", f2, BigDecimal("0.5")),
                allocation("A1", f3, BigDecimal("-0.5"))
              )

            val ledger = TaxLots.fifo(parentLots ++ twoChildren, noTickers, allocations)

            ledger.open must_== Map(
              f1 -> List(lot(f1, 60, 5, d0), lot(f1, 40, 10, d1)),
              f2 -> List(lot(f2, 6, 50, d0), lot(f2, 4, 100, d1)),
              f3 -> List(lot(f3, 3, 0, d0), lot(f3, 2, 0, d1))
            )
          }
        }

        "scales the allocations down to the whole cost when they add up to more" >> {
          val ops = List(
            buy("F1", 1000, 100, d0),
            leg(OperationKind.Spinoff, "F2", 10),
            leg(OperationKind.Spinoff, "F3", 5),
            leg(OperationKind.Spinoff, "F1", 0)
          )
          val allocations =
            List(allocation("A1", f2, BigDecimal("0.6")), allocation("A1", f3, BigDecimal("0.6")))

          // 0.6 and 0.6 become a half each, so F2 and F3 take 500 each and F1 keeps nothing.
          TaxLots.fifo(ops, noTickers, allocations).open must_== Map(
            f1 -> List(lot(f1, 100, 0, d0)),
            f2 -> List(lot(f2, 10, 50, d0)),
            f3 -> List(lot(f3, 5, 100, d0))
          )
        }

        "the lots of the new instrument add up to its units, though the division does not end" >> {
          val ops = threeLots("F1") ++ List(
            leg(OperationKind.Spinoff, "F2", 10),
            leg(OperationKind.Spinoff, "F1", 0),
            sell("F2", 50, 10, afterAction)
          )

          val ledger = TaxLots.fifo(ops, noTickers)

          ledger.open.get(f2) must beNone
          ledger.unmatched must beEmpty
        }

        "without a parent leg opens a lot at no cost on the day of the spin-off" >> {
          val ledger = TaxLots.fifo(List(leg(OperationKind.Spinoff, "F2", 10)), noTickers)

          ledger.open must_== Map(f2 -> List(lot(f2, 10, 0, actionDay)))
        }

        "with a parent that has no open lots opens a lot at no cost on the day of the spin-off" >> {
          val ledger =
            TaxLots.fifo(spinoff, noTickers, List(allocation("A1", f2, BigDecimal("0.2"))))

          ledger.open must_== Map(f2 -> List(lot(f2, 10, 0, actionDay)))
        }
      }
    }
  }
}
