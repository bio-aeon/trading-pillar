package st.btu.trading.pillar.services

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.services.IbkrCorporateActions.Notice
import st.btu.trading.pillar.testkit.IbkrFakes
import st.btu.trading.pillar.testkit.IbkrFakes.instrumentKey

import java.time.{LocalDate, ZoneId}

class IbkrRecordsSpec extends Specification {

  private val accountId = AccountId(7)
  private val newYork = ZoneId.of("America/New_York")
  private val reportDate = LocalDate.of(2026, 1, 16)
  private val dateTime = "20260115;093000"
  private val parentIsin = "KYG875721634"

  private def row(
    section: IbkrSection,
    id: String,
    attributes: (String, String)*
  ): IbkrStatementRow =
    IbkrFakes.row(
      accountId,
      section,
      id,
      reportDate,
      (Map("currency" -> "USD", "dateTime" -> dateTime) ++ attributes).toSeq*
    )

  // A purchase of 10 shares of the contract 111, which has the ISIN of the parent of a spin-off.
  private val trade = row(
    IbkrSection.Trade,
    "1001",
    "assetCategory" -> "STK",
    "conid" -> "111",
    "isin" -> parentIsin,
    "quantity" -> "10",
    "proceeds" -> "-1000"
  )

  private val dividend =
    row(IbkrSection.CashTransaction, "2001", "type" -> "Dividends", "amount" -> "5")

  private def leg(id: String, conid: String, quantity: String, overrides: (String, String)*) =
    row(
      IbkrSection.CorporateAction,
      id,
      (Seq(
        "actionID" -> "A1",
        "type" -> "TC",
        "conid" -> conid,
        "quantity" -> quantity
      ) ++ overrides)*
    )

  private def records(
    rows: List[IbkrStatementRow],
    parents: List[IbkrStatementRow] = Nil
  ): IbkrRecords.Result =
    IbkrRecords.of(rows, Set.empty, parents, newYork).toOption.get

  "IbkrRecords" >> {
    "of" >> {
      "translates each trade and cash transaction by its row, and the legs of an action together" >> {
        val rows = List(trade, leg("4001", "111", "-10"), dividend, leg("4002", "222", "5"))

        records(rows).records.map(record =>
          (record.row.transactionId, record.translations.map(_.kind))
        ) must contain(
          exactly(
            (IbkrTransactionId("1001"), List(OperationKind.Buy)),
            (IbkrTransactionId("2001"), List(OperationKind.Dividend)),
            (IbkrTransactionId("4001"), List(OperationKind.Conversion)),
            (IbkrTransactionId("4002"), List(OperationKind.Conversion))
          )
        )
      }

      "looks for the parent of a spin-off among the rows it is given as well as the parents" >> {
        val child = leg(
          "4001",
          "444",
          "5",
          "type" -> "SO",
          "description" -> s"NNND($parentIsin) SPINOFF 1 FOR 10 (NND.SPO, NNN SPINOFF, KYG875721000)"
        )
        val otherParent = row(IbkrSection.Trade, "1000", "conid" -> "999", "isin" -> parentIsin)
          .copy(reportDate = reportDate.minusDays(30))

        val marker = records(List(trade, child), List(otherParent)).records
          .flatMap(_.translations)
          .find(_.ref == OperationRef("corporate_action/4001/parent"))

        // The trade in the rows is the later of the two with the ISIN.
        marker.flatMap(_.instrument) must beSome(instrumentKey("111"))
      }

      "passes on the notices about the corporate actions" >> {
        val unknown = leg("4001", "111", "5", "type" -> "XX")

        records(List(trade, unknown)).notices must beLike[List[Notice]] {
          case List(Notice.NotInterpreted(action)) => action.id must_== "A1"
        }
      }

      "fails with the reason a row cannot be translated" >> {
        val noProceeds = row(IbkrSection.Trade, "1002", "assetCategory" -> "STK", "conid" -> "111")

        IbkrRecords.of(List(noProceeds), Set.empty, Nil, newYork) must beLeft[IbkrStatementError]
      }
    }
  }
}
