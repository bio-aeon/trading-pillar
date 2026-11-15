package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.tax.{CorporateActionAllocation, Lot, RealizedGain, TaxLots}
import st.btu.trading.pillar.testkit.IbkrFakes
import st.btu.trading.pillar.testkit.IbkrFakes.instrumentKey

import java.time.{LocalDate, ZoneId}

// The operations the interpretation gives, as the tax lots carry them through.
class IbkrCorporateActionsLotsSpec extends Specification {

  private val accountId = AccountId(7)
  private val newYork = ZoneId.of("America/New_York")
  private val usd = Currency("USD")

  private val boughtOn = LocalDate.of(2025, 1, 10)
  private val actionDay = LocalDate.of(2025, 3, 10)
  private val parentIsin = "KYG875721634"

  // 100 shares of the contract 111 bought for 1000 dollars on `boughtOn`.
  private val purchase = IbkrFakes.row(
    accountId,
    IbkrSection.Trade,
    "1001",
    boughtOn,
    "assetCategory" -> "STK",
    "conid" -> "111",
    "isin" -> parentIsin,
    "currency" -> "USD",
    "quantity" -> "100",
    "proceeds" -> "-1000",
    "dateTime" -> "20250110;093000"
  )

  private def leg(
    id: String,
    conid: String,
    quantity: String,
    overrides: (String, String)*
  ): IbkrStatementRow =
    IbkrFakes.row(
      accountId,
      IbkrSection.CorporateAction,
      id,
      actionDay,
      (Map(
        "actionID" -> "A1",
        "conid" -> conid,
        "currency" -> "USD",
        "quantity" -> quantity,
        "dateTime" -> "20250310;120000"
      ) ++ overrides).toSeq*
    )

  private def ledgerOf(
    rows: List[IbkrStatementRow],
    allocations: List[CorporateActionAllocation] = Nil
  ) = {
    val records = IbkrRecords.of(rows, Set.empty, Nil, newYork).toOption.get.records
    TaxLots.fifo(records.flatMap(_.translations), _ => None, allocations)
  }

  "IbkrCorporateActions" >> {
    "the lots of the operations it gives" >> {

      "a reverse split onto a new contract carries the cost and the date of the old shares to the new" >> {
        val rows = List(
          purchase,
          leg("4001", "111", "-100", "type" -> "RS"),
          leg("4002", "222", "4", "type" -> "RS")
        )

        ledgerOf(rows).open must_== Map(
          instrumentKey("222") -> List(Lot(instrumentKey("222"), 4, 250, boughtOn))
        )
      }

      "a merger paid in cash and stock sells the share of the old shares that the cash paid for" >> {
        // 100 shares become 50 shares worth 2000, with 500 in cash: a fifth of the whole.
        val rows = List(
          purchase,
          leg("4001", "111", "-100", "type" -> "TC", "proceeds" -> "500", "value" -> "-2500"),
          leg("4002", "222", "50", "type" -> "TC", "value" -> "2000")
        )

        val ledger = ledgerOf(rows)

        ledger.realized must_== List(
          RealizedGain(instrumentKey("111"), None, actionDay, 20, usd, 500, 200, 300, 59)
        )
        ledger.open must_== Map(
          instrumentKey("222") -> List(Lot(instrumentKey("222"), 50, 16, boughtOn))
        )
      }

      "a cash merger sells the shares at the cash it pays" >> {
        val rows = List(
          purchase,
          leg("4001", "111", "-100", "type" -> "TC", "proceeds" -> "1500", "value" -> "-1500")
        )

        val ledger = ledgerOf(rows)

        ledger.realized must_== List(
          RealizedGain(instrumentKey("111"), None, actionDay, 100, usd, 1500, 1000, 500, 59)
        )
        ledger.open must beEmpty
      }

      "a spin-off gives the new shares the share of the parent's cost that its allocation names" >> {
        val description = s"NNND($parentIsin) SPINOFF 1 FOR 10 (NND.SPO, NNN SPINOFF, KYG875721000)"
        val rows =
          List(purchase, leg("4001", "444", "10", "type" -> "SO", "description" -> description))
        val allocations =
          List(
            CorporateActionAllocation(OperationGroup("A1"), instrumentKey("444"), BigDecimal("0.2"))
          )

        ledgerOf(rows, allocations).open must_== Map(
          instrumentKey("111") -> List(Lot(instrumentKey("111"), 100, 8, boughtOn)),
          instrumentKey("444") -> List(Lot(instrumentKey("444"), 10, 20, boughtOn))
        )
      }

      "a stock dividend opens a lot at the market value IB reports" >> {
        val rows =
          List(purchase, leg("4001", "111", "50", "type" -> "SD", "value" -> "1595"))

        ledgerOf(rows).open must_== Map(
          instrumentKey("111") -> List(
            Lot(instrumentKey("111"), 100, 10, boughtOn),
            Lot(instrumentKey("111"), 50, BigDecimal("31.9"), actionDay)
          )
        )
      }

      "a forward split on the same contract scales the lots" >> {
        val rows = List(purchase, leg("4001", "111", "100", "type" -> "FS"))

        ledgerOf(rows).open must_== Map(
          instrumentKey("111") -> List(Lot(instrumentKey("111"), 200, 5, boughtOn))
        )
      }
    }
  }
}
