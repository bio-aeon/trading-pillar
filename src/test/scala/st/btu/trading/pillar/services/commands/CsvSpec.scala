package st.btu.trading.pillar.services.commands

import org.specs2.mutable.Specification

class CsvSpec extends Specification {

  "Csv" >> {
    "write" >> {
      "joins the header and every row with commas, CRLF-terminated" >> {
        val csv = Csv.write(List("a", "b"), List(List("1", "2"), List("3", "4")))

        csv must_== "a,b\r\n1,2\r\n3,4\r\n"
      }

      "produces just the header, CRLF-terminated, when there are no rows" >> {
        Csv.write(List("a", "b"), Nil) must_== "a,b\r\n"
      }

      "leaves a plain field bare" >> {
        Csv.write(List("h"), List(List("plain"))) must_== "h\r\nplain\r\n"
      }

      "quotes a field containing a comma" >> {
        Csv.write(List("h"), List(List("a,b"))) must_== "h\r\n\"a,b\"\r\n"
      }

      "quotes a field containing a double quote, doubling it" >> {
        val field = "say \"hi\"" // say "hi"
        Csv.write(List("h"), List(List(field))) must_== "h\r\n\"say \"\"hi\"\"\"\r\n"
      }

      "quotes a field containing a newline" >> {
        Csv.write(List("h"), List(List("line1\nline2"))) must_== "h\r\n\"line1\nline2\"\r\n"
      }
    }
  }
}
