package st.btu.trading.pillar.models.ibkr

import org.specs2.mutable.Specification

class IbkrStatementErrorSpec extends Specification {

  "IbkrStatementError" >> {
    "AccountNotInStatement" >> {
      "names no IB account in its text" >> {
        val error = IbkrStatementError.AccountNotInStatement(
          IbkrAccountId("U1111111"),
          List(IbkrAccountId("U9999999"))
        )

        (error.getMessage + error.toString) must
          (not(contain("U1111111")) and not(contain("U9999999")))
      }
    }
  }
}
