package st.btu.trading.pillar.analytics

import org.specs2.mutable.Specification

class ProportionsSpec extends Specification {

  "Proportions" >> {
    "apportion" >> {
      "shares the total in proportion to the weights" >> {
        Proportions.apportion(BigDecimal(10), List(BigDecimal(3), BigDecimal(2))) must_==
          List(BigDecimal(6), BigDecimal(4))
      }

      "makes the shares add up to the total, though the division does not end" >> {
        val shares = Proportions.apportion(BigDecimal(100), List.fill(3)(BigDecimal(1)))

        (shares.size, shares.sum) must_== (3, BigDecimal(100))
      }

      "gives no shares for no weights" >> {
        Proportions.apportion(BigDecimal(10), Nil) must beEmpty
      }
    }
  }
}
