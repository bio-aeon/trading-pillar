package st.btu.trading.pillar.models.domain

import org.specs2.mutable.Specification

// The strings below are what consumers store: renaming one breaks the data already stored.
class PersistedOperationValuesSpec extends Specification {

  "Broker" >> {
    "persists as the strings a consumer stores" >> {
      Broker.values.map(_.value) must contain(exactly("tinkoff"))
    }
  }

  "OperationKind" >> {
    "persists as the strings a consumer stores" >> {
      OperationKind.values.map(_.value) must contain(
        exactly(
          "buy",
          "sell",
          "security_in",
          "security_out",
          "deposit",
          "withdrawal",
          "dividend",
          "coupon",
          "interest",
          "fee",
          "tax",
          "other"
        )
      )
    }
  }

  "OperationState" >> {
    "persists as the strings a consumer stores" >> {
      OperationState.values.map(_.value) must contain(exactly("executed", "pending", "canceled"))
    }
  }
}
