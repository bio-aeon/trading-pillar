package st.btu.trading.pillar.services.commands

import org.specs2.mutable.Specification

class CommandReplySpec extends Specification {

  "CommandReply" >> {
    "prepare" >> {
      "applies the same markdown sanitization as the standalone sanitizer" >> {
        CommandReply.prepare("fast_sma *crossed* [slow_sma]") must_== "fast sma crossed slow sma"
      }

      "leaves text at or under MaxLength unchanged" >> {
        val text = "a" * CommandReply.MaxLength
        CommandReply.prepare(text) must_== text
      }

      "truncates text over MaxLength down to exactly MaxLength characters" >> {
        val text = "a" * (CommandReply.MaxLength + 1)
        val prepared = CommandReply.prepare(text)
        prepared.length must_== CommandReply.MaxLength
        prepared must_== "a" * CommandReply.MaxLength
      }
    }
  }
}
