package st.btu.trading.pillar.models.ibkr

import org.specs2.mutable.Specification

import java.time.{LocalDate, LocalDateTime}

class IbkrAttributesSpec extends Specification {

  "IbkrAttributes" >> {
    "value" >> {
      "is the text of the attribute without the space around it" >> {
        IbkrAttributes.value(Map("conid" -> " 265598 "), "conid") must beSome("265598")
      }

      "is absent for an attribute IB did not send" >> {
        IbkrAttributes.value(Map("conid" -> "1"), "symbol") must beNone
      }

      "is absent for the placeholders IB sends for no value" >> {
        List("", " ", "-", "--", "N/A").map(placeholder =>
          IbkrAttributes.value(Map("isin" -> placeholder), "isin")
        ) must_== List.fill(5)(None)
      }

      "keeps text that only starts like a placeholder" >> {
        IbkrAttributes.value(Map("symbol" -> "N/AB"), "symbol") must beSome("N/AB")
      }
    }

    "date" >> {
      "reads a date written yyyyMMdd" >> {
        IbkrAttributes.date("20260115") must beSome(LocalDate.of(2026, 1, 15))
      }

      "rejects a day the month does not have" >> {
        IbkrAttributes.date("20260230") must beNone
      }

      "rejects a date written any other way" >> {
        List("2026-01-15", "15/01/2026", "260115", "").flatMap(IbkrAttributes.date) must beEmpty
      }
    }

    "dateTime" >> {
      "reads a date and a time written yyyyMMdd;HHmmss" >> {
        IbkrAttributes.dateTime("20260115;093000") must beSome(
          LocalDateTime.of(2026, 1, 15, 9, 30, 0)
        )
      }

      "reads a date alone as the start of its day" >> {
        IbkrAttributes.dateTime("20260115") must beSome(LocalDateTime.of(2026, 1, 15, 0, 0, 0))
      }

      "rejects a time that is not a time" >> {
        IbkrAttributes.dateTime("20260115;256100") must beNone
      }

      "rejects another separator" >> {
        IbkrAttributes.dateTime("20260115 093000") must beNone
      }
    }

    "decimal" >> {
      "reads a number with a sign and decimals" >> {
        IbkrAttributes.decimal("-1502.50") must beSome(BigDecimal("-1502.50"))
      }

      "ignores thousands separators" >> {
        IbkrAttributes.decimal("1,234,567.89") must beSome(BigDecimal("1234567.89"))
      }

      "rejects text that is not a number" >> {
        IbkrAttributes.decimal("ten") must beNone
      }
    }
  }
}
