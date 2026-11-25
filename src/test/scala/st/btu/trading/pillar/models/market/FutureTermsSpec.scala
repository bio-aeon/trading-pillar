package st.btu.trading.pillar.models.market

import org.specs2.mutable.Specification

import java.time.LocalDate

class FutureTermsSpec extends Specification {

  private val expiration = LocalDate.of(2027, 3, 18)

  private def terms(priceStep: String, priceStepValue: String): FutureTerms =
    FutureTerms(
      Underlying("SBER"),
      expiration.minusDays(1),
      expiration,
      BigDecimal(priceStep),
      BigDecimal(priceStepValue)
    )

  "FutureTerms" >> {
    "pointValue" >> {
      "is the value of a price step over the step" >> {
        terms("0.01", "0.79").pointValue must_== BigDecimal(79)
      }

      "keeps the fraction of a point worth less than a unit" >> {
        terms("10", "16.9").pointValue must_== BigDecimal("1.69")
      }
    }

    "expiredOn" >> {
      "is false on the expiration date itself" >> {
        terms("1", "1").expiredOn(expiration) must beFalse
      }

      "is false before the expiration date" >> {
        terms("1", "1").expiredOn(expiration.minusDays(1)) must beFalse
      }

      "is true the day after the expiration date" >> {
        terms("1", "1").expiredOn(expiration.plusDays(1)) must beTrue
      }
    }
  }
}
