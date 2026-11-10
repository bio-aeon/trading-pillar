package st.btu.trading.pillar.models.ibkr

import org.specs2.mutable.Specification

// The strings below are what consumers store: renaming one breaks the data already stored.
class PersistedIbkrValuesSpec extends Specification {

  "IbkrSection" >> {
    "persists as the strings a consumer stores" >> {
      IbkrSection.values.map(_.value) must contain(
        exactly("trade", "cash_transaction", "transfer", "corporate_action")
      )
    }
  }
}
