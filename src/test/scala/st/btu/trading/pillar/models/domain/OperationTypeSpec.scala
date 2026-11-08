package st.btu.trading.pillar.models.domain

import org.specs2.mutable.Specification

class OperationTypeSpec extends Specification {

  "OperationType" >> {
    "decode" >> {
      "decodes a known raw value to its named case" >> {
        OperationType.decode("OPERATION_TYPE_BUY") must_== OperationType.Buy
      }

      "falls back to Unknown(raw), round-tripping the raw value through .value" >> {
        val decoded = OperationType.decode("OPERATION_TYPE_SOME_FUTURE_TYPE")
        decoded must_== OperationType.Unknown("OPERATION_TYPE_SOME_FUTURE_TYPE")
        decoded.value must_== "OPERATION_TYPE_SOME_FUTURE_TYPE"
      }
    }
  }

  "OperationStatus" >> {
    "decode" >> {
      "decodes the known values to their named cases" >> {
        OperationStatus.decode("OPERATION_STATE_EXECUTED") must_== OperationStatus.Executed
        OperationStatus.decode("OPERATION_STATE_CANCELED") must_== OperationStatus.Canceled
        OperationStatus.decode("OPERATION_STATE_PROGRESS") must_== OperationStatus.Progress
      }

      "falls back to Unknown(raw) for anything else" >> {
        OperationStatus.decode("OPERATION_STATE_SOME_FUTURE_STATE") must_==
          OperationStatus.Unknown("OPERATION_STATE_SOME_FUTURE_STATE")
      }
    }
  }
}
