package st.btu.trading.pillar.data

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.AccountId
import tofu.logging.LogTree

class ProcessContextSpec extends Specification {

  "ProcessContext" >> {
    "logged fields" >> {
      "are traceId as a string and accountId as a number" >> {
        LogTree(ProcessContext("trace-1", AccountId(42))).noSpaces must_==
          """{"traceId":"trace-1","accountId":42}"""
      }
    }
  }
}
