package st.btu.trading.pillar.models.tg

import org.specs2.mutable.Specification

class MarkdownSanitizerSpec extends Specification {

  "MarkdownSanitizer" >> {
    "sanitize" >> {
      "turns underscores into spaces" >> {
        MarkdownSanitizer.sanitize("fast_sma_crossed") must_== "fast sma crossed"
      }

      "removes every *, `, [ and ] without a replacement" >> {
        MarkdownSanitizer.sanitize("*bold* `code` [link](url)") must_== "bold code link(url)"
      }

      "leaves text with no markup or underscores unchanged" >> {
        MarkdownSanitizer.sanitize("AAPL rose above 100") must_== "AAPL rose above 100"
      }
    }
  }
}
