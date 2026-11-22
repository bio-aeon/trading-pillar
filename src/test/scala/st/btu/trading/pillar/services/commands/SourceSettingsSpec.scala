package st.btu.trading.pillar.services.commands

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.market.SourceSetting

class SourceSettingsSpec extends Specification {

  "SourceSettings" >> {
    "parse" >> {
      "reads binance, whatever its case, as the setting that has no board or symbol" >> {
        List("binance", "BINANCE", "Binance").map(SourceSettings.parse) must_==
          List.fill(3)(Some(SourceSetting.Binance))
      }
    }

    "parseChoice" >> {
      "assigns binance" >> {
        SourceSettings.parseChoice(List("binance")) must
          beSome[SourceSettings.Choice](SourceSettings.Choice.Assign(SourceSetting.Binance))
      }

      "refuses a word after binance, which has no board or symbol to give" >> {
        SourceSettings.parseChoice(List("binance", "BTCUSDT")) must beNone
      }
    }
  }
}
