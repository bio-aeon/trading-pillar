package st.btu.trading.pillar.services.commands

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments

class RepliesSpec extends Specification {

  "Replies" >> {
    "price" >> {
      "from 1 up" >> {
        Fragments.foreach(
          List(
            "187.5" -> "187.50",
            "150" -> "150.00",
            "1" -> "1.00",
            "12345.678" -> "12345.68",
            "2.005" -> "2.01"
          )
        ) { case (value, shown) =>
          s"shows $value at two decimals as $shown" >> {
            Replies.price(BigDecimal(value)) must_== shown
          }
        }
      }

      "below 1" >> {
        Fragments.foreach(
          List("0.123456" -> "0.1235", "0.000008234567" -> "0.000008235", "0.99994" -> "0.9999")
        ) { case (value, shown) =>
          s"shows $value at four significant digits as $shown" >> {
            Replies.price(BigDecimal(value)) must_== shown
          }
        }

        Fragments.foreach(
          List(
            "0.5" -> "0.5",
            "0.10" -> "0.1",
            "0.00000823" -> "0.00000823",
            "0.000008230000000000" -> "0.00000823"
          )
        ) { case (value, shown) =>
          s"drops the trailing zeros of $value, which shows as $shown" >> {
            Replies.price(BigDecimal(value)) must_== shown
          }
        }

        "never uses scientific notation" >> {
          Replies.price(BigDecimal("0.000000000000000001")) must_== "0.000000000000000001"
          Replies.price(BigDecimal("8.23E-6")) must_== "0.00000823"
        }

        "shows 1.00 when four significant digits round it up to 1" >> {
          Replies.price(BigDecimal("0.99996")) must_== "1.00"
        }

        "shows zero as 0" >> {
          Replies.price(BigDecimal("0.000000000000000000")) must_== "0"
        }
      }
    }
  }
}
