package st.btu.trading.pillar.gateways.ibkr

import org.specs2.mutable.Specification

import scala.util.Try

class FlexWebServiceConfigSpec extends Specification {

  "FlexWebServiceConfig" >> {
    "construction" >> {
      "rejects no attempts at all" >> {
        Try(FlexWebServiceConfig(maxAttempts = 0)).isFailure must beTrue
      }

      "accepts a single attempt" >> {
        Try(FlexWebServiceConfig(maxAttempts = 1)).isSuccess must beTrue
      }
    }
  }
}
