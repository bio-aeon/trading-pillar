package st.btu.trading.pillar.analytics

import org.specs2.mutable.Specification

class ReturnsSpec extends Specification {

  "Returns" >> {
    "simple" >> {
      "computes v(t) / v(t-1) - 1 for each consecutive pair" >> {
        Returns.simple(Vector[BigDecimal](100, 110, 99)) must_== Vector[BigDecimal](0.1, -0.1)
      }

      "is empty when fewer than two values" >> {
        Returns.simple(Vector.empty) must_== Vector.empty
        Returns.simple(Vector[BigDecimal](100)) must_== Vector.empty
      }

      "skips a pair whose previous value is not positive, rather than dividing by it" >> {
        // pairs: (0, 100) prev <= 0, skipped; (100, -5) kept, -5/100 - 1 = -1.05;
        // (-5, 50) prev <= 0, skipped.
        Returns.simple(Vector[BigDecimal](0, 100, -5, 50)) must_== Vector[BigDecimal](-1.05)
      }
    }

    "log" >> {
      "computes ln(v(t) / v(t-1)) for each consecutive pair, rounded to scale 8" >> {
        Returns.log(Vector[BigDecimal](100, 110)) must_== Vector(BigDecimal("0.09531018"))
      }

      "is 0 when the value doesn't change" >> {
        Returns.log(Vector[BigDecimal](100, 100)) must_== Vector[BigDecimal](0)
      }

      "is empty when fewer than two values" >> {
        Returns.log(Vector.empty) must_== Vector.empty
        Returns.log(Vector[BigDecimal](100)) must_== Vector.empty
      }

      "skips a pair whose previous value is not positive, rather than dividing by it" >> {
        // pairs: (0, 100) prev <= 0, skipped; (100, 50) kept, ln(0.5) = -0.69314718 (scale 8).
        Returns.log(Vector[BigDecimal](0, 100, 50)) must_== Vector(BigDecimal("-0.69314718"))
      }
    }
  }
}
