package st.btu.trading.pillar.models.ibkr

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.{AccountId, OperationRef}
import st.btu.trading.pillar.testkit.IbkrFakes

import java.time.LocalDate

class IbkrStatementRowSpec extends Specification {

  private def row(section: IbkrSection, id: String): IbkrStatementRow =
    IbkrFakes.row(AccountId(1), section, id, LocalDate.of(2026, 1, 15))

  private val trade = row(IbkrSection.Trade, "123")

  "IbkrStatementRow" >> {
    "ref" >> {
      "is the section and the transaction id" >> {
        trade.ref must_== OperationRef("trade/123")
        row(IbkrSection.CashTransaction, "2001").ref must_== OperationRef("cash_transaction/2001")
      }
    }

    "refWith" >> {
      "adds the suffix after a slash" >> {
        trade.refWith("commission") must_== OperationRef("trade/123/commission")
      }
    }

    "owns" >> {
      "the row's own ref" >> {
        trade.owns(OperationRef("trade/123")) must beTrue
      }

      "a ref under the row's own" >> {
        trade.owns(OperationRef("trade/123/commission")) must beTrue
        trade.owns(OperationRef("trade/123/EUR")) must beTrue
      }

      "not the ref of a row whose id only starts with the same digits" >> {
        trade.owns(OperationRef("trade/1234")) must beFalse
        trade.owns(OperationRef("trade/1234/commission")) must beFalse
      }

      "not the ref of a row of another section" >> {
        trade.owns(OperationRef("transfer/123")) must beFalse
      }
    }
  }
}
