package st.btu.trading.pillar.alerts

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.Ticker

import java.time.{ZoneOffset, ZonedDateTime}

class AlertSpec extends Specification {

  "Alert" >> {
    "dedupHash" >> {
      "is the lower-case hex SHA-256 of symbol, kind, title, body and trigger value joined by |" >> {
        val alert = Alert(
          ruleKey = "rule-1",
          symbol = Some(Ticker("AAPL")),
          kind = AlertKind.TargetHit,
          title = "t1",
          body = "b",
          triggerValue = Some(BigDecimal("1.5")),
          at = ZonedDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC)
        )

        // SHA-256 of the text "AAPL|target_hit|t1|b|1.5", computed outside the JVM
        alert.dedupHash must_== "31fab1e228b5542accaec9083dbc976c849b2dc350aa3c8e9a983a3d4929e472"
      }
    }
  }
}
