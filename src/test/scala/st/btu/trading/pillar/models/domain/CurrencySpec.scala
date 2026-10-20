package st.btu.trading.pillar.models.domain

import org.specs2.mutable.Specification
import st.btu.trading.pillar.models.domain.Currency.value

class CurrencySpec extends Specification {

  "Currency" >> {
    "apply" >> {

      "upper-cases a code with no upper-case letter" >> {
        Currency("rub").value must_== "RUB"
      }

      "trims whitespace around the code" >> {
        Currency(" usd ").value must_== "USD"
      }

      List("RUB", "GBp").foreach { code =>
        s"keeps $code as given" >> {
          Currency(code).value must_== code
        }
      }

      "reads every spelling of one currency as the same value" >> {
        Currency("rub") must_== Currency(" RUB ")
      }

      "keeps the minor unit GBp distinct from GBP" >> {
        Currency("GBp") must_!= Currency("GBP")
      }
    }
  }
}
