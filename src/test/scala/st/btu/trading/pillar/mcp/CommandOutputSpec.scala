package st.btu.trading.pillar.mcp

import ch.linkyard.mcp.protocol.Content
import org.specs2.mutable.Specification
import st.btu.trading.pillar.gateways.CapturedReply

import java.nio.charset.StandardCharsets

class CommandOutputSpec extends Specification {

  private def utf8(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)

  // Arrays compare by reference, so an image is compared by its type and bytes.
  private def comparable(content: Content): Any =
    content match {
      case Content.Image(data, mimeType, _, _) => ("image", mimeType, data.toList)
      case other => other
    }

  private def render(replies: CapturedReply*): List[Any] =
    CommandOutput.render(replies.toList).map(comparable)

  "CommandOutput" >> {
    "render" >> {

      "texts" >> {

        "returns a single reply as text" >> {
          render(CapturedReply.Text("Working normally.")) must_== List(
            Content.Text("Working normally.")
          )
        }

        "joins several replies into one text, separated by a blank line" >> {
          render(CapturedReply.Text("first"), CapturedReply.Text("second")) must_== List(
            Content.Text("first\n\nsecond")
          )
        }

        "lists the button labels after the text of a keyboard reply" >> {
          render(
            CapturedReply.TextWithKeyboard(
              "Choose a command:",
              List(List("/portfolio", "/positions"), List("/alerts"))
            )
          ) must_== List(
            Content.Text("Choose a command:\nButtons: /portfolio, /positions, /alerts")
          )
        }

        "says so when the command produced no reply" >> {
          render() must_== List(Content.Text("The command produced no reply."))
        }
      }

      "documents" >> {

        "shows a text document as its file name and content" >> {
          render(
            CapturedReply.Document("dividends-2026.csv", utf8("ticker,amount\nAAPL,0.25"), None)
          )
            .must_==(List(Content.Text("File dividends-2026.csv:\nticker,amount\nAAPL,0.25")))
        }

        "puts the caption of a document before it" >> {
          render(CapturedReply.Document("notes.txt", utf8("hello"), Some("Your notes")))
            .must_==(List(Content.Text("Your notes\nFile notes.txt:\nhello")))
        }

        "keeps a document of exactly the limit whole" >> {
          val content = "x" * CommandOutput.MaxDocumentChars
          render(CapturedReply.Document("limit.csv", utf8(content), None)) must_== List(
            Content.Text(s"File limit.csv:\n$content")
          )
        }

        "cuts a document longer than the limit and says how much is shown" >> {
          val content = "x" * (CommandOutput.MaxDocumentChars + 5)
          render(CapturedReply.Document("big.csv", utf8(content), None)) must_== List(
            Content.Text(s"File big.csv, first 20000 of 20005 characters:\n${"x" * 20000}")
          )
        }

        "does not show a document that is not valid UTF-8" >> {
          render(CapturedReply.Document("data.bin", Array[Byte](-119, 80, 78, 71), None))
            .must_==(List(Content.Text("File data.bin: binary content, 4 bytes, not shown")))
        }

        "does not show a document that contains NUL characters" >> {
          render(CapturedReply.Document("data.bin", Array[Byte](65, 0, 66), None))
            .must_==(List(Content.Text("File data.bin: binary content, 3 bytes, not shown")))
        }
      }

      "photos" >> {

        "returns a photo as a PNG image" >> {
          render(CapturedReply.Photo(Array[Byte](1, 2, 3), None)) must_== List(
            ("image", "image/png", List[Byte](1, 2, 3))
          )
        }

        "puts the images after the text" >> {
          render(
            CapturedReply.Text("Backtest #1"),
            CapturedReply.Photo(Array[Byte](9), None)
          ) must_== List(Content.Text("Backtest #1"), ("image", "image/png", List[Byte](9)))
        }

        "uses the caption of a photo as text" >> {
          render(CapturedReply.Photo(Array[Byte](9), Some("Equity curve"))) must_== List(
            Content.Text("Equity curve"),
            ("image", "image/png", List[Byte](9))
          )
        }
      }
    }
  }
}
