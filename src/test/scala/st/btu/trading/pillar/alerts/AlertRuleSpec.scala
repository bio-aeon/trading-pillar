package st.btu.trading.pillar.alerts

import io.circe.syntax.*
import org.specs2.mutable.Specification

class AlertRuleSpec extends Specification {

  "PriceCrossed.Direction" >> {
    "name" >> {
      "is \"above\" for Above and \"below\" for Below" >> {
        PriceCrossed.Direction.Above.name must_== "above"
        PriceCrossed.Direction.Below.name must_== "below"
      }
    }

    "fromName" >> {
      "is the inverse of name for every case" >> {
        PriceCrossed.Direction.fromName("above") must_== Some(PriceCrossed.Direction.Above)
        PriceCrossed.Direction.fromName("below") must_== Some(PriceCrossed.Direction.Below)
      }

      "is None for anything else" >> {
        PriceCrossed.Direction.fromName("sideways") must beNone
      }
    }

    "circe codec" >> {
      "encodes to the lowercase name and decodes back to the same value" >> {
        (PriceCrossed.Direction.Above: PriceCrossed.Direction).asJson.noSpaces must_== "\"above\""
        io.circe.parser.decode[PriceCrossed.Direction]("\"below\"") must
          beRight(PriceCrossed.Direction.Below: PriceCrossed.Direction)
      }

      "fails to decode an unrecognized string" >> {
        io.circe.parser.decode[PriceCrossed.Direction]("\"sideways\"") must beLeft
      }
    }
  }
}
